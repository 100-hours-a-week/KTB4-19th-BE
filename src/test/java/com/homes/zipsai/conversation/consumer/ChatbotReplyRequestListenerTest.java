package com.homes.zipsai.conversation.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.homes.zipsai.conversation.ai.AiConverseRequest;
import com.homes.zipsai.conversation.ai.error.AiConverseErrorType;
import com.homes.zipsai.conversation.ai.error.AiConverseException;
import com.homes.zipsai.conversation.domain.ChatbotReplyOutbox;
import com.homes.zipsai.conversation.domain.ChatbotReplyOutboxStatus;
import com.homes.zipsai.conversation.domain.Conversation;
import com.homes.zipsai.conversation.dto.response.ConversationReplyResponse;
import com.homes.zipsai.conversation.dto.response.MessageResponse;
import com.homes.zipsai.conversation.outbox.ChatbotReplyWorker;
import com.homes.zipsai.conversation.outbox.ChatbotReplyWorkerProperties;
import com.homes.zipsai.conversation.repository.ChatbotReplyOutboxRepository;
import com.homes.zipsai.conversation.service.ConversationReplyService;
import com.homes.zipsai.conversation.service.ConversationService;
import com.homes.zipsai.conversation.service.DiscardedMessage;
import com.homes.zipsai.conversation.service.PendingAiReply;
import com.homes.zipsai.global.exception.InternalServerException;
import com.homes.zipsai.global.logging.StructuredLogger;
import com.rabbitmq.client.Channel;

import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class ChatbotReplyRequestListenerTest {

    private static final long EVENT_ID = 900L;
    private static final long DELIVERY_TAG = 37L;
    private static final String LOCK_TOKEN = "consumer-listener-token";

    @Mock
    ChatbotReplyWorker chatbotReplyWorker;

    @Mock
    ConversationReplyService conversationReplyService;

    @Mock
    ConversationService conversationService;

    @Mock
    ChatbotReplyOutboxRepository chatbotReplyOutboxRepository;

    @Mock
    StructuredLogger structuredLogger;

    @Mock
    Channel channel;

    @Mock
    ConversationReplyResponse savedReply;

    private ChatbotReplyRequestListener listener;
    private ChatbotReplyOutbox outbox;
    private PendingAiReply pending;

    @BeforeEach
    void setUp() {
        listener = new ChatbotReplyRequestListener(chatbotReplyWorker, conversationReplyService, conversationService,
            chatbotReplyOutboxRepository, new ObjectMapper(), structuredLogger,
            new ChatbotReplyWorkerProperties(Duration.ofSeconds(130), Duration.ofSeconds(280)));
        outbox = ChatbotReplyOutbox.startNextMessage(mock(com.homes.zipsai.conversation.domain.Message.class),
            1L, "consumer-trace", LOCK_TOKEN, LocalDateTime.now(), null, LocalDateTime.now());
        ReflectionTestUtils.setField(outbox, "id", EVENT_ID);
    }

    @ParameterizedTest
    @CsvSource({"900, 37", "1234, 81"})
    @DisplayName("입력 eventId만 동기 처리하고 서비스 반환 후 해당 delivery tag만 ACK한다")
    void acknowledgesSynchronousResult(long eventId, long deliveryTag) throws Exception {
        String previousTraceId = MDC.get("traceId");
        preparePending(eventId);
        given(conversationReplyService.replySynchronously(pending)).willAnswer(invocation -> {
            then(channel).shouldHaveNoInteractions();

            assertThat(MDC.get("traceId")).isEqualTo("consumer-trace");
            outbox.complete();
            return Optional.of(savedReply);
        });

        listener.onMessage(message("{\"eventId\":" + eventId + "}", deliveryTag), channel);

        InOrder order = inOrder(chatbotReplyWorker, conversationReplyService, channel);
        order.verify(chatbotReplyWorker).claimAndPrepareReply(eventId);

        order.verify(conversationReplyService).replySynchronously(pending);

        order.verify(channel).basicAck(deliveryTag, false);

        then(conversationReplyService).should(never()).reply(any());

        then(chatbotReplyWorker).shouldHaveNoMoreInteractions();

        then(chatbotReplyOutboxRepository).should().findById(eventId);

        then(chatbotReplyOutboxRepository).shouldHaveNoMoreInteractions();

        then(channel).shouldHaveNoMoreInteractions();

        assertThat(MDC.get("traceId")).isEqualTo(previousTraceId);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{", "[]", "1", "{}", "{\"eventId\":null}",
        "{\"eventId\":\"900\"}", "{\"eventId\":true}", "{\"eventId\":900,\"attempt\":1}",
        "{\"eventId\":1.5}", "{\"eventId\":9223372036854775808}", "{\"eventId\":900} {}",
        "{\"eventId\":900,\"eventId\":1234}"})
    @DisplayName("숫자 eventId 단일 필드 객체가 아니면 업무 처리 전에 형식 오류를 알린다")
    void rejectsInvalidPayload(String body) {
        assertThatThrownBy(() -> listener.onMessage(message(body, DELIVERY_TAG), channel))
            .isInstanceOf(RuntimeException.class);

        verifyNoInteractions(chatbotReplyOutboxRepository, chatbotReplyWorker, conversationReplyService,
            conversationService);
    }

    @ParameterizedTest
    @EnumSource(value = ChatbotReplyOutboxStatus.class, names = {"COMPLETED", "FAILED"})
    @DisplayName("이미 종료된 이벤트는 추가 처리 없이 ACK한다")
    void acknowledgesTerminalEvent(ChatbotReplyOutboxStatus status) throws Exception {
        ReflectionTestUtils.setField(outbox, "status", status);
        given(chatbotReplyOutboxRepository.findById(EVENT_ID)).willReturn(Optional.of(outbox));

        listener.onMessage(message("{\"eventId\":900}", DELIVERY_TAG), channel);

        then(channel).should().basicAck(DELIVERY_TAG, false);

        verifyNoInteractions(chatbotReplyWorker, conversationReplyService, conversationService);

        then(channel).shouldHaveNoMoreInteractions();
    }

    @ParameterizedTest
    @ValueSource(strings = {"LOOKUP", "CLAIM", "REPLY"})
    @DisplayName("AI 실행 전 조회·선점·동기 경로의 DB 연결 오류는 실패 처리 없이 NACK한다")
    void requeuesDatabaseFailure(String stage) throws Exception {
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException("AI 실행 전 DB 오류");
        if ("LOOKUP".equals(stage)) {
            given(chatbotReplyOutboxRepository.findById(EVENT_ID)).willThrow(failure);
        } else if ("CLAIM".equals(stage)) {
            given(chatbotReplyOutboxRepository.findById(EVENT_ID)).willReturn(Optional.of(outbox));
            given(chatbotReplyWorker.claimAndPrepareReply(EVENT_ID)).willThrow(failure);
        } else {
            preparePending(EVENT_ID);
            given(pending.conversation().getId()).willReturn(10L);
            given(pending.aiRequest().turnId()).willReturn("listener-turn");
            given(conversationReplyService.replySynchronously(pending)).willThrow(failure);
        }

        listener.onMessage(message("{\"eventId\":900}", DELIVERY_TAG), channel);

        then(channel).should().basicNack(DELIVERY_TAG, false, true);

        assertThat(outbox.getAttemptCount()).isZero();

        verifyNoInteractions(conversationService);

        then(conversationReplyService).should(never()).reply(any());

        if ("REPLY".equals(stage)) {
            then(conversationReplyService).should().replySynchronously(pending);

            then(structuredLogger).should().chatbotReplyFailed(EVENT_ID, 10L, "listener-turn", failure);
        } else {
            verifyNoInteractions(conversationReplyService);
        }

        if ("CLAIM".equals(stage)) {
            verifyNoInteractions(structuredLogger);
        }

        then(channel).shouldHaveNoMoreInteractions();
    }

    @ParameterizedTest
    @EnumSource(value = AiConverseErrorType.class, names = {"HTTP_ERROR", "STREAM_ERROR", "INCOMPLETE_STREAM",
        "RESPONSE_JSON_ERROR", "TURN_ID_MISMATCH", "INVALID_RESPONSE", "REQUEST_SERIALIZATION_ERROR"})
    @DisplayName("AI 오류 종류를 기존 처리로 전달하고 재시도 대기 반환 후 NACK한다")
    void requeuesRetryableFailure(AiConverseErrorType type) throws Exception {
        preparePending(EVENT_ID);
        given(conversationReplyService.replySynchronously(pending)).willThrow(failure(type));
        given(conversationService.handleAiReplyFailure(pending, type)).willAnswer(invocation -> {
            then(channel).shouldHaveNoInteractions();

            assertThat(outbox.getAttemptCount()).isZero();
            outbox.retry(0);
            return Optional.empty();
        });

        listener.onMessage(message("{\"eventId\":900}", DELIVERY_TAG), channel);

        InOrder order = inOrder(conversationReplyService, conversationService, channel);
        order.verify(conversationReplyService).replySynchronously(pending);

        order.verify(conversationService).handleAiReplyFailure(pending, type);

        order.verify(channel).basicNack(DELIVERY_TAG, false, true);

        assertThat(outbox.getAttemptCount()).isZero();

        then(conversationReplyService).should().replySynchronously(pending);

        then(conversationReplyService).should(never()).reply(any());

        then(conversationService).shouldHaveNoMoreInteractions();

        then(channel).shouldHaveNoMoreInteractions();
    }

    @ParameterizedTest
    @EnumSource(value = AiConverseErrorType.class, names = {"TIMEOUT", "CONNECTION_FAILURE",
        "SERVICE_UNAVAILABLE", "GATEWAY_TIMEOUT", "HTTP_ERROR"})
    @DisplayName("재시도 가능 여부와 관계없이 기존 처리가 최종 실패를 확정하면 ACK한다")
    void acknowledgesFinalFailure(AiConverseErrorType type) throws Exception {
        preparePending(EVENT_ID);
        given(conversationReplyService.replySynchronously(pending)).willThrow(failure(type));
        given(conversationService.handleAiReplyFailure(pending, type)).willAnswer(invocation -> {
            then(channel).shouldHaveNoInteractions();
            outbox.fail();
            return Optional.of(new DiscardedMessage(1L, 10L));
        });

        listener.onMessage(message("{\"eventId\":900}", DELIVERY_TAG), channel);

        InOrder order = inOrder(conversationReplyService, conversationService, channel);
        order.verify(conversationReplyService).replySynchronously(pending);

        order.verify(conversationService).handleAiReplyFailure(pending, type);

        order.verify(channel).basicAck(DELIVERY_TAG, false);

        then(chatbotReplyOutboxRepository).should().findById(EVENT_ID);

        then(chatbotReplyOutboxRepository).shouldHaveNoMoreInteractions();

        then(conversationService).shouldHaveNoMoreInteractions();

        then(channel).shouldHaveNoMoreInteractions();
    }

    @Test
    @DisplayName("활성 PROCESSING 중복은 상태·실패 횟수·토큰·시각을 보존하고 추가 AI 실행 없이 NACK한다")
    void requeuesActiveDuplicate() throws Exception {
        LocalDateTime startedAt = LocalDateTime.now().minusSeconds(10);
        outbox.claim(LOCK_TOKEN, startedAt);
        outbox.recordFailure();
        given(chatbotReplyOutboxRepository.findById(EVENT_ID)).willReturn(Optional.of(outbox));
        given(chatbotReplyWorker.claimAndPrepareReply(EVENT_ID)).willReturn(Optional.empty());
        given(chatbotReplyOutboxRepository.currentTime()).willReturn(LocalDateTime.now());

        listener.onMessage(message("{\"eventId\":900}", DELIVERY_TAG), channel);

        then(channel).should().basicNack(DELIVERY_TAG, false, true);

        assertThat(outbox.getStatus()).isEqualTo(ChatbotReplyOutboxStatus.PROCESSING);

        assertThat(outbox.getAttemptCount()).isEqualTo(1);

        assertThat(outbox.getLockToken()).isEqualTo(LOCK_TOKEN);

        assertThat(outbox.getProcessingStartedAt()).isEqualTo(startedAt);

        verifyNoInteractions(conversationReplyService, conversationService);

        then(channel).shouldHaveNoMoreInteractions();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("빈 선점 또는 준비 중 마감 오류의 만료 작업은 기존 최종 정리 반환 후 ACK한다")
    void acknowledgesCleanupAfterEmptyClaim(boolean deadlineFailure) throws Exception {
        LocalDateTime now = LocalDateTime.now();
        ReflectionTestUtils.setField(outbox, "createdAt", now.minusSeconds(300));
        given(chatbotReplyOutboxRepository.findById(EVENT_ID)).willReturn(Optional.of(outbox));
        if (deadlineFailure) {
            given(chatbotReplyWorker.claimAndPrepareReply(EVENT_ID)).willThrow(new InternalServerException());
        } else {
            given(chatbotReplyWorker.claimAndPrepareReply(EVENT_ID)).willReturn(Optional.empty());
        }
        given(chatbotReplyOutboxRepository.currentTime()).willReturn(now);
        given(chatbotReplyOutboxRepository.findCleanupCandidate(EVENT_ID, now.minusSeconds(280),
            now.minus(Conversation.IDLE_TIME_TO_CLOSE))).willReturn(Optional.of(outbox));
        doAnswer(invocation -> {
            then(channel).shouldHaveNoInteractions();
            outbox.fail();
            return null;
        }).when(conversationReplyService).failReply(EVENT_ID, 0, LOCK_TOKEN);

        listener.onMessage(message("{\"eventId\":900}", DELIVERY_TAG), channel);

        InOrder order = inOrder(conversationReplyService, channel);
        order.verify(conversationReplyService).failReply(EVENT_ID, 0, LOCK_TOKEN);

        order.verify(channel).basicAck(DELIVERY_TAG, false);

        then(conversationReplyService).shouldHaveNoMoreInteractions();

        then(channel).shouldHaveNoMoreInteractions();
    }

    @Test
    @DisplayName("동기 서비스의 트랜잭션이 커밋된 뒤에만 ACK한다")
    void acknowledgesAfterCommit() throws Exception {
        preparePending(EVENT_ID);
        AtomicBoolean committed = new AtomicBoolean();
        doAnswer(invocation -> {
            assertThat(committed).isTrue();
            return null;
        }).when(channel).basicAck(DELIVERY_TAG, false);
        try (AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext(TransactionConfig.class)) {
            context.registerBean(ChatbotReplyRequestListener.class, () -> listener);
            TransactionTemplate transactions =
                new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            given(conversationReplyService.replySynchronously(pending))
                .willAnswer(invocation -> transactions.execute(status -> {
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            committed.set(true);
                        }
                    });
                    outbox.complete();
                    return Optional.of(savedReply);
                }));

            context.getBean(ChatbotReplyRequestListener.class)
                .onMessage(message("{\"eventId\":900}", DELIVERY_TAG), channel);
        }

        then(channel).should().basicAck(DELIVERY_TAG, false);
    }

    private void preparePending(long eventId) {
        ReflectionTestUtils.setField(outbox, "id", eventId);
        outbox.claim(LOCK_TOKEN, LocalDateTime.now());
        pending = new PendingAiReply(eventId, 0, LOCK_TOKEN, 1L, mock(Conversation.class),
            mock(MessageResponse.class), mock(AiConverseRequest.class));
        given(chatbotReplyOutboxRepository.findById(eventId)).willReturn(Optional.of(outbox));
        given(chatbotReplyWorker.claimAndPrepareReply(eventId)).willReturn(Optional.of(pending));
    }

    private static AiConverseException failure(AiConverseErrorType type) {
        return new AiConverseException(type, new InternalServerException(), null);
    }

    private static Message message(String body, long tag) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setDeliveryTag(tag);
        return new Message(body.getBytes(StandardCharsets.UTF_8), properties);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class TransactionConfig {

        @Bean
        PlatformTransactionManager transactionManager() {
            return new DataSourceTransactionManager(
                new DriverManagerDataSource("jdbc:h2:mem:chatbot_consumer_listener_tx", "sa", ""));
        }
    }
}
