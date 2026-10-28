package com.homes.zipsai.conversation.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import com.homes.zipsai.building.service.ResidentRoomService;
import com.homes.zipsai.common.service.RedisMessagePublisher;
import com.homes.zipsai.conversation.ai.AiConverseClient;
import com.homes.zipsai.conversation.ai.AiConverseRequest;
import com.homes.zipsai.conversation.ai.AiConverseResponse;
import com.homes.zipsai.conversation.ai.AiRoute;
import com.homes.zipsai.conversation.ai.error.AiConverseErrorType;
import com.homes.zipsai.conversation.ai.error.AiConverseException;
import com.homes.zipsai.conversation.config.ChatbotReplyPubSubProperties;
import com.homes.zipsai.conversation.domain.ChatbotReplyOutbox;
import com.homes.zipsai.conversation.domain.ChatbotReplyOutboxStatus;
import com.homes.zipsai.conversation.domain.Conversation;
import com.homes.zipsai.conversation.domain.ConversationType;
import com.homes.zipsai.conversation.domain.Message;
import com.homes.zipsai.conversation.domain.MessageType;
import com.homes.zipsai.conversation.domain.SenderType;
import com.homes.zipsai.conversation.dto.response.MessageResponse;
import com.homes.zipsai.conversation.event.ChatbotReplyEvent;
import com.homes.zipsai.conversation.repository.ChatbotReplyOutboxRepository;
import com.homes.zipsai.conversation.repository.MessageRepository;
import com.homes.zipsai.conversation.service.ChatbotReplyShutdownSignal;
import com.homes.zipsai.conversation.service.ConversationReplyLock;
import com.homes.zipsai.conversation.service.ConversationReplyService;
import com.homes.zipsai.conversation.service.ConversationService;
import com.homes.zipsai.conversation.service.PendingAiReply;
import com.homes.zipsai.global.exception.AiUnavailableException;
import com.homes.zipsai.global.exception.ApiException;
import com.homes.zipsai.global.exception.InternalServerException;
import com.homes.zipsai.global.logging.StructuredLogger;
import com.homes.zipsai.user.domain.User;
import com.homes.zipsai.user.domain.UserRole;

@SpringBootTest
@DirtiesContext
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:tc:mysql:8.4.11:///zipsai_chatbot_reply_retry?TC_DAEMON=true",
    "spring.datasource.username=test",
    "spring.datasource.password=test",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.jpa.hibernate.naming.physical-strategy=org.hibernate.boot.model.naming.PhysicalNamingStrategyStandardImpl",
    "spring.jpa.defer-datasource-initialization=false",
    "spring.flyway.enabled=true",
    "app.conversation.reply-worker.attempt-timeout=130s",
    "app.conversation.reply-worker.request-timeout=280s",
    "app.conversation.reply-lock.ttl=150s"
})
class ChatbotReplyRetryIntegrationTest {

    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 10, 26, 12, 0);
    private static final Duration LOCK_TTL = Duration.ofSeconds(150);
    private static final String CHANNEL = "test:chatbot-reply-retry";
    private static final GenericContainer<?> REDIS =
        new GenericContainer<>(DockerImageName.parse("redis:8.2.10-alpine")).withExposedPorts(6379);

    @Autowired
    ConversationService conversationService;

    @Autowired
    ConversationReplyLock replyLock;

    @Autowired
    MessageRepository messageRepository;

    @Autowired
    EntityManager entityManager;

    @Autowired
    TransactionTemplate transactions;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    StructuredLogger structuredLogger;

    @MockitoSpyBean
    ChatbotReplyOutboxRepository outboxRepository;

    @MockitoBean
    AiConverseClient aiConverseClient;

    @MockitoBean
    ResidentRoomService residentRoomService;

    @MockitoBean
    RedisMessagePublisher redisMessagePublisher;

    @MockitoBean
    ChatbotReplyShutdownSignal shutdownSignal;

    private final List<String> lockKeys = new ArrayList<>();
    private ConversationReplyService replyService;

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        REDIS.start();
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @AfterAll
    static void stopRedis() {
        REDIS.stop();
    }

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM Chatbot_reply_outbox");
        replyService = new ConversationReplyService(conversationService, aiConverseClient, redisMessagePublisher,
            new ChatbotReplyPubSubProperties(CHANNEL), replyLock, shutdownSignal, structuredLogger);
        checkedAt(CREATED_AT.plusSeconds(10));
    }

    @AfterEach
    void removeInputLocks() {
        if (!lockKeys.isEmpty()) {
            redis.delete(lockKeys);
        }
        MDC.clear();
    }

    @Test
    @DisplayName("요청 JSON 생성 실패는 기존 실패 횟수를 유지하고 재시도 대기로 넘긴다")
    void preservesFailureCountForBackendError() {
        Job job = job(2, 0, 0);
        given(aiConverseClient.converse(eq(job.pending().aiRequest()), any()))
            .willThrow(failure(AiConverseErrorType.REQUEST_SERIALIZATION_ERROR));

        replyService.reply(job.pending());

        assertPending(job, 2);
    }

    @Test
    @DisplayName("타임아웃은 재시도 없이 최종 실패하고 자기 입력 락을 해제한다")
    void failsImmediatelyForNonRetryableError() {
        Job job = job(1, 0, 0);
        given(aiConverseClient.converse(eq(job.pending().aiRequest()), any()))
            .willThrow(failure(AiConverseErrorType.TIMEOUT));

        replyService.reply(job.pending());

        assertFailed(job, 1, new AiUnavailableException());
    }

    @Test
    @DisplayName("최초 실패를 포함한 세 번째 횟수 증가 오류는 실패 횟수 3을 남기고 최종 실패한다")
    void failsOnThirdLimitedFailure() {
        Job job = job(2, 0, 0);
        given(aiConverseClient.converse(eq(job.pending().aiRequest()), any()))
            .willReturn(null);

        replyService.reply(job.pending());

        assertFailed(job, 3, new InternalServerException());

        then(aiConverseClient).should().converse(eq(job.pending().aiRequest()), any());
    }

    @Test
    @DisplayName("서로 다른 횟수 증가 오류는 토큰을 교체한 다음 실행에서도 누적하여 세 번째에 실패한다")
    void accumulatesDifferentLimitedFailuresAcrossClaims() {
        Job job = job(0, 0, 0);
        AiConverseResponse mismatchedResponse = new AiConverseResponse(AiConverseResponse.SUCCESS_CODE,
            "other-turn", new AiConverseResponse.Data(AiRoute.KNOWLEDGE, null, "관리실에 문의해 주세요.", null));
        given(aiConverseClient.converse(any(), any())).willThrow(
            failure(AiConverseErrorType.HTTP_ERROR),
            failure(AiConverseErrorType.RESPONSE_JSON_ERROR)).willReturn(mismatchedResponse);

        replyService.reply(job.pending());

        assertPending(job, 1);

        job = claim(job);
        replyService.reply(job.pending());

        assertPending(job, 2);

        job = claim(job);
        replyService.reply(job.pending());

        assertFailed(job, 3, new InternalServerException());

        then(aiConverseClient).should(times(3)).converse(any(), any());
    }

    @Test
    @DisplayName("AI 호출 중 토큰이 인계되면 이전 실행의 오류는 새 실행의 상태·횟수·메시지·락을 바꾸지 않는다")
    void ignoresFailureAfterOwnershipTransfer() {
        Job job = job(1, 0, 0);
        String nextToken = UUID.randomUUID().toString();
        LocalDateTime nextStartedAt = CREATED_AT.plusSeconds(20);
        given(aiConverseClient.converse(eq(job.pending().aiRequest()), any())).willAnswer(invocation -> {
            jdbc.update("UPDATE Chatbot_reply_outbox SET lock_token = ?, processing_started_at = ? WHERE event_id = ?",
                nextToken, nextStartedAt, job.eventId());
            redis.opsForValue().set(key(job), nextToken, LOCK_TTL);
            checkedAt(nextStartedAt);
            throw failure(AiConverseErrorType.HTTP_ERROR);
        });

        replyService.reply(job.pending());

        assertThat(outbox(job))
            .hasFieldOrPropertyWithValue("status", ChatbotReplyOutboxStatus.PROCESSING)
            .hasFieldOrPropertyWithValue("attemptCount", 1)
            .hasFieldOrPropertyWithValue("lockToken", nextToken)
            .hasFieldOrPropertyWithValue("processingStartedAt", nextStartedAt)
            .hasFieldOrPropertyWithValue("createdAt", CREATED_AT);

        assertThat(messageRepository.existsById(job.messageId())).isTrue();

        assertThat(jdbc.queryForObject("SELECT last_message_at FROM Conversations WHERE conversation_id = ?",
            LocalDateTime.class, job.conversationId())).isEqualTo(job.lastMessageAt());

        assertThat(redis.opsForValue().get(key(job))).isEqualTo(nextToken);

        then(redisMessagePublisher).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("재시도 대기로 반환한 이전 실행이 다시 호출돼도 AI 호출과 실패 횟수 증가를 반복하지 않는다")
    void ignoresRepeatedExecutionAfterPendingTransition() {
        Job job = job(0, 0, 0);
        given(aiConverseClient.converse(eq(job.pending().aiRequest()), any()))
            .willThrow(failure(AiConverseErrorType.STREAM_ERROR));

        replyService.reply(job.pending());
        replyService.reply(job.pending());

        assertPending(job, 1);

        then(aiConverseClient).should().converse(eq(job.pending().aiRequest()), any());
    }

    @Test
    @DisplayName("횟수 유지 오류라도 전체 요청 마감에 도달하면 재시도 대신 최종 실패한다")
    void failsAtDeadlineInsteadOfDeferringBackendError() {
        Job job = job(0, 200, 0);
        LocalDateTime deadline = CREATED_AT.plusSeconds(280);
        checkedAt(deadline.minusNanos(1000));
        given(aiConverseClient.converse(eq(job.pending().aiRequest()), any())).willAnswer(invocation -> {
            checkedAt(deadline);
            throw failure(AiConverseErrorType.REQUEST_SERIALIZATION_ERROR);
        });

        replyService.reply(job.pending());

        assertFailed(job, 0, new AiUnavailableException());
    }

    @Test
    @DisplayName("서버 종료 중 발생한 재시도 가능 오류는 처리 중 상태와 실패 횟수·메시지·입력 락을 유지한다")
    void leavesProcessingJobForShutdownRecovery() {
        Job job = job(1, 0, 0);
        given(aiConverseClient.converse(eq(job.pending().aiRequest()), any()))
            .willAnswer(invocation -> {
                given(shutdownSignal.isShuttingDown()).willReturn(true);
                throw failure(AiConverseErrorType.HTTP_ERROR);
            });

        replyService.reply(job.pending());

        assertThat(outbox(job))
            .hasFieldOrPropertyWithValue("status", ChatbotReplyOutboxStatus.PROCESSING)
            .hasFieldOrPropertyWithValue("attemptCount", 1)
            .hasFieldOrPropertyWithValue("lockToken", job.token());

        assertThat(messageRepository.existsById(job.messageId())).isTrue();

        assertThat(redis.opsForValue().get(key(job))).isEqualTo(job.token());

        then(aiConverseClient).should().converse(eq(job.pending().aiRequest()), any());

        then(redisMessagePublisher).shouldHaveNoInteractions();
    }

    private Job job(int failureCount, int startedOffset, int lastMessageOffset) {
        Job job = transactions.execute(transaction -> {
            User user = new User(UUID.randomUUID() + "@example.com", "password", "입주민", null);
            user.selectRole(UserRole.RESIDENT);
            entityManager.persist(user);
            Conversation conversation = new Conversation(user, ConversationType.INQUIRY, "보일러 문의");
            LocalDateTime messageAt = CREATED_AT.plusSeconds(lastMessageOffset);
            conversation.updateLastMessageAt(messageAt);
            entityManager.persist(conversation);
            Message message = new Message(conversation, "보일러가 안 켜져요", SenderType.RESIDENT,
                MessageType.TEXT, UUID.randomUUID().toString());
            entityManager.persist(message);
            String token = UUID.randomUUID().toString();
            LocalDateTime startedAt = CREATED_AT.plusSeconds(startedOffset);
            ChatbotReplyOutbox outbox = ChatbotReplyOutbox.startNextMessage(message, user.getId(),
                UUID.randomUUID().toString(), token, messageAt.minusSeconds(10), startedAt);
            entityManager.persist(outbox);
            entityManager.flush();
            jdbc.update("UPDATE Chatbot_reply_outbox SET created_at = ?, attempt_count = ? WHERE event_id = ?",
                CREATED_AT, failureCount, outbox.getId());
            entityManager.refresh(outbox);
            AiConverseRequest request = new AiConverseRequest(null, null, null, null, message.getTurnId(),
                outbox.getTraceId(), null, null, null, List.of(), null);
            PendingAiReply pending = PendingAiReply.of(outbox, token, conversation,
                MessageResponse.of(message, List.of()), request);
            return new Job(outbox.getId(), conversation.getId(), message.getId(), token, startedAt, messageAt, pending);
        });
        lockKeys.add(key(job));
        redis.opsForValue().set(key(job), job.token(), LOCK_TTL);
        return job;
    }

    private Job claim(Job job) {
        String token = UUID.randomUUID().toString();
        LocalDateTime startedAt = CREATED_AT.plusSeconds(10);
        PendingAiReply pending = transactions.execute(transaction -> {
            ChatbotReplyOutbox outbox = outbox(job);
            outbox.claim(token, startedAt);
            return PendingAiReply.of(outbox, token, job.pending().conversation(), job.pending().residentMessage(),
                job.pending().aiRequest());
        });
        redis.opsForValue().set(key(job), token, LOCK_TTL);
        return new Job(job.eventId(), job.conversationId(), job.messageId(), token, startedAt, job.lastMessageAt(),
            pending);
    }

    private void checkedAt(LocalDateTime now) {
        doReturn(now).when(outboxRepository).currentTime();
    }

    private void assertPending(Job job, int failureCount) {
        assertThat(outbox(job))
            .hasFieldOrPropertyWithValue("status", ChatbotReplyOutboxStatus.PENDING)
            .hasFieldOrPropertyWithValue("attemptCount", failureCount)
            .hasFieldOrPropertyWithValue("lockToken", job.token())
            .hasFieldOrPropertyWithValue("processingStartedAt", job.startedAt())
            .hasFieldOrPropertyWithValue("createdAt", CREATED_AT);

        assertThat(messageRepository.existsById(job.messageId())).isTrue();

        assertThat(jdbc.queryForObject("SELECT last_message_at FROM Conversations WHERE conversation_id = ?",
            LocalDateTime.class, job.conversationId())).isEqualTo(job.lastMessageAt());

        assertThat(redis.opsForValue().get(key(job))).isEqualTo(job.token());

        then(redisMessagePublisher).shouldHaveNoInteractions();
    }

    private void assertFailed(Job job, int failureCount, ApiException expectedError) {
        assertThat(outbox(job))
            .hasFieldOrPropertyWithValue("status", ChatbotReplyOutboxStatus.FAILED)
            .hasFieldOrPropertyWithValue("attemptCount", failureCount);

        assertThat(messageRepository.existsById(job.messageId())).isFalse();

        assertThat(redis.hasKey(key(job))).isFalse();

        ArgumentCaptor<ChatbotReplyEvent> event = ArgumentCaptor.forClass(ChatbotReplyEvent.class);

        then(redisMessagePublisher).should().publish(eq(CHANNEL), event.capture());

        assertThat(event.getValue().eventType()).isEqualTo(ChatbotReplyEvent.Type.FAILED);

        assertThat(event.getValue().failure())
            .hasFieldOrPropertyWithValue("conversationId", job.conversationId())
            .hasFieldOrPropertyWithValue("code", expectedError.code)
            .hasFieldOrPropertyWithValue("message", expectedError.getMessage())
            .hasFieldOrPropertyWithValue("details", expectedError.details);
    }

    private ChatbotReplyOutbox outbox(Job job) {
        return outboxRepository.findById(job.eventId()).orElseThrow();
    }

    private static AiConverseException failure(AiConverseErrorType errorType) {
        return new AiConverseException(errorType, new AiUnavailableException(), null);
    }

    private static String key(Job job) {
        return "conversation:replying:" + job.conversationId();
    }

    private record Job(Long eventId, Long conversationId, Long messageId, String token,
                       LocalDateTime startedAt, LocalDateTime lastMessageAt, PendingAiReply pending) {
    }
}
