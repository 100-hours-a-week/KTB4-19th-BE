package com.homes.zipsai.conversation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InOrder;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionSystemException;

import com.homes.zipsai.common.service.RedisMessagePublisher;
import com.homes.zipsai.conversation.ai.AiConverseClient;
import com.homes.zipsai.conversation.ai.AiConverseRequest;
import com.homes.zipsai.conversation.ai.AiConverseResponse;
import com.homes.zipsai.conversation.ai.AiRoute;
import com.homes.zipsai.conversation.ai.error.AiConverseErrorType;
import com.homes.zipsai.conversation.ai.error.AiConverseException;
import com.homes.zipsai.conversation.config.ChatbotReplyPubSubProperties;
import com.homes.zipsai.conversation.domain.Conversation;
import com.homes.zipsai.conversation.domain.ConversationType;
import com.homes.zipsai.conversation.domain.MessageType;
import com.homes.zipsai.conversation.domain.SenderType;
import com.homes.zipsai.conversation.dto.response.ConversationReplyDeltaResponse;
import com.homes.zipsai.conversation.dto.response.ConversationReplyResponse;
import com.homes.zipsai.conversation.dto.response.MessageResponse;
import com.homes.zipsai.conversation.event.ChatbotReplyEvent;
import com.homes.zipsai.conversation.outbox.ChatbotReplyRequestSender;
import com.homes.zipsai.conversation.repository.ChatbotReplyOutboxRepository;
import com.homes.zipsai.global.config.AsyncConfig;
import com.homes.zipsai.global.exception.AiUnavailableException;
import com.homes.zipsai.global.exception.InternalServerException;
import com.homes.zipsai.global.logging.StructuredLogger;

@SpringJUnitConfig(ConversationReplySynchronousTest.Config.class)
class ConversationReplySynchronousTest {

    private static final long USER_ID = 1L;
    private static final long CONVERSATION_ID = 10L;
    private static final String TURN_ID = "consumer-turn";
    private static final String LOCK_TOKEN = "consumer-token";
    private static final String CHANNEL = "test:chatbot-consumer-reply";

    @Autowired
    ConversationReplyService replyService;

    @MockitoBean
    ConversationService conversationService;

    @MockitoBean
    AiConverseClient aiConverseClient;

    @MockitoBean
    RedisMessagePublisher redisMessagePublisher;

    @MockitoBean
    ConversationReplyLock replyLock;

    @MockitoBean
    ChatbotReplyShutdownSignal shutdownSignal;

    @MockitoBean
    StructuredLogger structuredLogger;

    @MockitoBean
    ChatbotReplyOutboxRepository chatbotReplyOutboxRepository;

    @MockitoBean
    ChatbotReplyRequestSender chatbotReplyRequestSender;

    private PendingAiReply pending;
    private AiConverseResponse aiResponse;
    private ConversationReplyResponse reply;

    @BeforeEach
    void setUp() {
        Conversation conversation = Conversation.builder().type(ConversationType.INQUIRY).title("보일러 문의").build();
        ReflectionTestUtils.setField(conversation, "id", CONVERSATION_ID);
        MessageResponse resident = new MessageResponse(20L, SenderType.RESIDENT, MessageType.TEXT,
            "보일러가 안 켜져요", List.of(), null, null);
        AiConverseRequest request = new AiConverseRequest(null, null, null, null, TURN_ID,
            "consumer-trace", null, null, null, List.of(), null);
        pending = new PendingAiReply(900L, 2, LOCK_TOKEN, USER_ID, conversation, resident, request);
        aiResponse = response(TURN_ID);
        MessageResponse assistant = new MessageResponse(21L, SenderType.ASSISTANT, MessageType.TEXT,
            aiResponse.reply(), List.of(), null, null);
        reply = new ConversationReplyResponse(CONVERSATION_ID, assistant, null);
        given(conversationService.remainingReplyTime(pending)).willReturn(Optional.of(Duration.ofSeconds(130)));
    }

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("동기 호출은 프록시를 거쳐도 호출 스레드에서 델타·저장·완료 발행을 마치고 답변을 반환한다")
    void returnsSavedReplyAfterSynchronousExecution() {
        Thread caller = Thread.currentThread();
        given(aiConverseClient.converse(eq(pending.aiRequest()), any())).willAnswer(invocation -> {
            assertThat(Thread.currentThread()).isSameAs(caller);
            Consumer<String> delta = invocation.getArgument(1);
            delta.accept("관리실에 ");
            delta.accept("문의해 주세요.");
            return aiResponse;
        });
        given(conversationService.saveAiReply(pending, aiResponse)).willReturn(Optional.of(reply));

        Optional<ConversationReplyResponse> result = replyService.replySynchronously(pending);

        assertThat(result).contains(reply);

        InOrder order = inOrder(aiConverseClient, conversationService, replyLock, redisMessagePublisher);
        order.verify(conversationService).remainingReplyTime(pending);

        order.verify(aiConverseClient).converse(eq(pending.aiRequest()), any());

        order.verify(redisMessagePublisher).publish(CHANNEL, ChatbotReplyEvent.of(USER_ID,
            new ConversationReplyDeltaResponse(CONVERSATION_ID, 1, "관리실에 ")));

        order.verify(redisMessagePublisher).publish(CHANNEL, ChatbotReplyEvent.of(USER_ID,
            new ConversationReplyDeltaResponse(CONVERSATION_ID, 2, "문의해 주세요.")));

        order.verify(conversationService).saveAiReply(pending, aiResponse);

        order.verify(replyLock).release(CONVERSATION_ID, LOCK_TOKEN);

        order.verify(redisMessagePublisher).publish(CHANNEL, ChatbotReplyEvent.of(USER_ID, reply));
    }

    @Test
    @DisplayName("DB 접근·트랜잭션 저장 오류는 동일 응답만 재시도하고 AI 호출과 실패 처리를 반복하지 않는다")
    void retriesOnlySavingTheSameResponse() {
        given(aiConverseClient.converse(eq(pending.aiRequest()), any())).willReturn(aiResponse);
        given(conversationService.saveAiReply(pending, aiResponse))
            .willThrow(new TransientDataAccessResourceException("DB 연결 실패"))
            .willThrow(new TransactionSystemException("트랜잭션 실패"))
            .willReturn(Optional.of(reply));

        assertThat(replyService.replySynchronously(pending)).contains(reply);

        then(conversationService).should(times(3)).saveAiReply(eq(pending), same(aiResponse));

        then(aiConverseClient).should().converse(eq(pending.aiRequest()), any());

        then(conversationService).should(never()).handleAiReplyFailure(any(), any());

        then(conversationService).should(never()).discardUnansweredMessage(any(), anyInt(), any());

        then(redisMessagePublisher).should().publish(CHANNEL, ChatbotReplyEvent.of(USER_ID, reply));
    }

    @Test
    @DisplayName("저장 재시도 예산이 끝나면 추가 AI 호출이나 실패 정리 없이 오류를 호출자에게 전달한다")
    void reportsSaveDeadlineWithoutAnotherAiCall() {
        given(conversationService.remainingReplyTime(pending)).willReturn(Optional.of(Duration.ofMillis(30)));
        given(aiConverseClient.converse(eq(pending.aiRequest()), any())).willReturn(aiResponse);
        given(conversationService.saveAiReply(pending, aiResponse))
            .willThrow(new TransientDataAccessResourceException("DB 연결 실패"));

        assertThatThrownBy(() -> replyService.replySynchronously(pending)).isInstanceOf(InternalServerException.class);

        then(conversationService).should().saveAiReply(pending, aiResponse);

        then(aiConverseClient).should().converse(eq(pending.aiRequest()), any());

        then(conversationService).should(never()).discardUnansweredMessage(any(), anyInt(), any());

        then(conversationService).should(never()).handleAiReplyFailure(any(), any());

        then(replyLock).shouldHaveNoInteractions();

        then(redisMessagePublisher).shouldHaveNoInteractions();
    }

    @ParameterizedTest
    @EnumSource(AiConverseErrorType.class)
    @DisplayName("동기 AI 오류는 기존 분류를 유지한 채 호출자에게 전달하고 저장·실패 정리를 먼저 실행하지 않는다")
    void propagatesClassifiedAiFailure(AiConverseErrorType type) {
        AiConverseException failure = new AiConverseException(type, new AiUnavailableException(), null);
        given(aiConverseClient.converse(eq(pending.aiRequest()), any())).willThrow(failure);

        assertThatThrownBy(() -> replyService.replySynchronously(pending)).isSameAs(failure);

        then(conversationService).should(never()).saveAiReply(any(), any());

        then(conversationService).should(never()).handleAiReplyFailure(any(), any());

        then(conversationService).should(never()).discardUnansweredMessage(any(), anyInt(), any());

        then(replyLock).shouldHaveNoInteractions();

        then(redisMessagePublisher).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("다른 turn ID의 응답은 기존 검증 오류로 전달하고 답변을 저장하지 않는다")
    void rejectsResponseForAnotherTurn() {
        given(aiConverseClient.converse(eq(pending.aiRequest()), any())).willReturn(response("another-turn"));

        assertThatThrownBy(() -> replyService.replySynchronously(pending))
            .isInstanceOfSatisfying(AiConverseException.class,
                failure -> assertThat(failure.getErrorType()).isEqualTo(AiConverseErrorType.TURN_ID_MISMATCH));

        then(conversationService).should(never()).saveAiReply(any(), any());

        then(redisMessagePublisher).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("최종 저장에서 소유권이 없으면 성공 답변을 반환하거나 잠금 해제·완료 발행을 하지 않는다")
    void doesNotReportSuccessWhenSaveLosesOwnership() {
        given(aiConverseClient.converse(eq(pending.aiRequest()), any())).willReturn(aiResponse);
        given(conversationService.saveAiReply(pending, aiResponse)).willReturn(Optional.empty());

        assertThat(replyService.replySynchronously(pending)).isEmpty();

        then(conversationService).should().saveAiReply(pending, aiResponse);

        then(replyLock).shouldHaveNoInteractions();

        then(redisMessagePublisher).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("AI 호출 전 DB 조회 오류는 AI 실행과 실패 횟수 처리 없이 호출자에게 전달한다")
    void propagatesDatabaseFailureBeforeAiExecution() {
        TransientDataAccessResourceException failure = new TransientDataAccessResourceException("DB 연결 실패");
        given(conversationService.remainingReplyTime(pending)).willThrow(failure);

        assertThatThrownBy(() -> replyService.replySynchronously(pending)).isSameAs(failure);

        then(aiConverseClient).shouldHaveNoInteractions();

        then(conversationService).should(never()).handleAiReplyFailure(any(), any());

        then(conversationService).should(never()).discardUnansweredMessage(any(), anyInt(), any());
    }

    private static AiConverseResponse response(String turnId) {
        return new AiConverseResponse(AiConverseResponse.SUCCESS_CODE, turnId,
            new AiConverseResponse.Data(AiRoute.KNOWLEDGE, null, "관리실에 문의해 주세요.", null));
    }

    @Configuration(proxyBeanMethods = false)
    @Import({ConversationReplyService.class, AsyncConfig.class})
    static class Config {

        @Bean
        ChatbotReplyPubSubProperties chatbotReplyPubSubProperties() {
            return new ChatbotReplyPubSubProperties(CHANNEL);
        }
    }
}
