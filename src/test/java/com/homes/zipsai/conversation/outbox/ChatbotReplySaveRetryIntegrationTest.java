package com.homes.zipsai.conversation.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.doAnswer;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import com.homes.zipsai.building.service.ResidentRoomService;
import com.homes.zipsai.common.service.RedisMessagePublisher;
import com.homes.zipsai.conversation.ai.AiConverseClient;
import com.homes.zipsai.conversation.ai.AiConverseRequest;
import com.homes.zipsai.conversation.ai.AiConverseResponse;
import com.homes.zipsai.conversation.ai.AiRoute;
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
import com.homes.zipsai.global.exception.InternalServerException;
import com.homes.zipsai.global.logging.StructuredLogger;
import com.homes.zipsai.user.domain.User;
import com.homes.zipsai.user.domain.UserRole;

@SpringBootTest
@DirtiesContext
@Timeout(30)
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:tc:mysql:8.4.11:///zipsai_chatbot_reply_save_retry",
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
class ChatbotReplySaveRetryIntegrationTest {

    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 10, 26, 12, 0);
    private static final Duration LOCK_TTL = Duration.ofSeconds(150);
    private static final String CHANNEL = "test:chatbot-reply-save-retry";
    private static final String ANSWER = "관리실에 보일러 점검을 요청해 주세요.";
    private static final int FAILURE_COUNT = 2;
    private static final GenericContainer<?> REDIS =
        new GenericContainer<>(DockerImageName.parse("redis:8.2.10-alpine")).withExposedPorts(6379);

    @Autowired
    ConversationService conversationService;

    @Autowired
    ConversationReplyLock replyLock;

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

    @MockitoSpyBean
    MessageRepository messageRepository;

    @MockitoSpyBean(name = "transactionManager")
    PlatformTransactionManager transactionManager;

    @MockitoBean
    AiConverseClient aiConverseClient;

    @MockitoBean
    ResidentRoomService residentRoomService;

    @MockitoBean
    RedisMessagePublisher redisMessagePublisher;

    @MockitoBean
    ChatbotReplyShutdownSignal shutdownSignal;

    private final AtomicReference<LocalDateTime> checkedAt = new AtomicReference<>();
    private final List<String> lockKeys = new ArrayList<>();
    private final List<SaveAttempt> saves = new ArrayList<>();
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
        checkedAt.set(CREATED_AT.plusSeconds(10));
        doAnswer(invocation -> checkedAt.get()).when(outboxRepository).currentTime();
        replyService = new ConversationReplyService(conversationService, aiConverseClient, redisMessagePublisher,
            new ChatbotReplyPubSubProperties(CHANNEL), replyLock, shutdownSignal, structuredLogger);
    }

    @AfterEach
    void removeInputLocks() {
        if (!lockKeys.isEmpty()) {
            redis.delete(lockKeys);
        }
        MDC.clear();
    }

    @Test
    @DisplayName("저장 롤백 뒤 같은 답변만 다시 저장하고 상태·횟수·입력 락을 유지한다")
    void retriesSameAnswerAfterStorageRollback() {
        Job job = job(0, 0);
        failSaves(1);
        afterRollback(() -> {
            assertProcessing(job, job.token(), job.startedAt());

            assertThat(jdbc.queryForObject("SELECT current_route FROM Conversations WHERE conversation_id = ?",
                String.class, job.conversationId())).isNull();

            assertThat(jdbc.queryForObject("SELECT last_message_at FROM Conversations WHERE conversation_id = ?",
                LocalDateTime.class, job.conversationId())).isEqualTo(job.lastMessageAt());
        });

        replyService.reply(job.pending());

        assertThat(saves).hasSize(2);

        assertThat(saves).allSatisfy(save -> {
            assertThat(save.content()).isEqualTo(ANSWER);

            assertThat(save.turnId()).isEqualTo(job.pending().aiRequest().turnId());
        });

        assertThat(outbox(job))
            .hasFieldOrPropertyWithValue("status", ChatbotReplyOutboxStatus.COMPLETED)
            .hasFieldOrPropertyWithValue("attemptCount", FAILURE_COUNT)
            .hasFieldOrPropertyWithValue("lockToken", job.token())
            .hasFieldOrPropertyWithValue("processingStartedAt", job.startedAt());

        assertThat(messageRepository.existsById(job.messageId())).isTrue();

        assertThat(assistantCount(job)).isEqualTo(1);

        assertThat(jdbc.queryForObject("SELECT content FROM Messages WHERE conversation_id = ?"
            + " AND sender_type = 'ASSISTANT'", String.class, job.conversationId())).isEqualTo(ANSWER);

        assertThat(redis.hasKey(key(job))).isFalse();

        assertThat(publishedEvent().eventType()).isEqualTo(ChatbotReplyEvent.Type.DONE);

        then(aiConverseClient).should().converse(eq(job.pending().aiRequest()), any());
    }

    @ParameterizedTest
    @CsvSource({"0, 0, 130", "200, 0, 280", "0, -200, 100"})
    @DisplayName("DB 저장 실패를 재시도해도 시도·전체 요청·대화 종료 마감에 도달하면 횟수 증가 없이 실패한다")
    void stopsSavingAtDeadline(int startedOffset, int lastMessageOffset, int deadlineOffset) {
        Job job = job(startedOffset, lastMessageOffset);
        LocalDateTime deadline = CREATED_AT.plusSeconds(deadlineOffset);
        checkedAt.set(deadline.minusSeconds(2));
        failSaves(Integer.MAX_VALUE);
        AtomicInteger rollbacks = new AtomicInteger();
        afterRollback(() -> {
            assertProcessing(job, job.token(), job.startedAt());
            checkedAt.set(rollbacks.incrementAndGet() == 1 ? deadline.minusSeconds(1) : deadline);
        });

        replyService.reply(job.pending());

        assertThat(saves).hasSize(2);

        assertThat(outbox(job))
            .hasFieldOrPropertyWithValue("status", ChatbotReplyOutboxStatus.FAILED)
            .hasFieldOrPropertyWithValue("attemptCount", FAILURE_COUNT);

        assertThat(messageRepository.existsById(job.messageId())).isFalse();

        assertThat(assistantCount(job)).isZero();

        assertThat(redis.hasKey(key(job))).isFalse();

        ChatbotReplyEvent event = publishedEvent();

        assertThat(event.eventType()).isEqualTo(ChatbotReplyEvent.Type.FAILED);

        assertThat(event.failure()).hasFieldOrPropertyWithValue("code", new InternalServerException().code);

        then(aiConverseClient).should().converse(eq(job.pending().aiRequest()), any());
    }

    @Test
    @DisplayName("저장 롤백 후 다른 실행이 인계받으면 이전 답변 저장과 실패 정리·락 해제·이벤트를 중단한다")
    void stopsSavingAfterOwnershipTransfer() {
        Job job = job(0, 0);
        String nextToken = UUID.randomUUID().toString();
        LocalDateTime nextStartedAt = CREATED_AT.plusSeconds(20);
        failSaves(1);
        afterRollback(() -> {
            jdbc.update("UPDATE Chatbot_reply_outbox SET lock_token = ?, processing_started_at = ? WHERE event_id = ?",
                nextToken, nextStartedAt, job.eventId());
            redis.opsForValue().set(key(job), nextToken, LOCK_TTL);
            checkedAt.set(nextStartedAt);
        });

        replyService.reply(job.pending());

        assertThat(saves).hasSize(1);

        assertProcessing(job, nextToken, nextStartedAt);

        then(aiConverseClient).should().converse(eq(job.pending().aiRequest()), any());
    }

    @Test
    @DisplayName("저장 롤백 후 서버 종료가 시작되면 추가 저장과 실패 정리 없이 작업·메시지·락을 유지한다")
    void stopsSavingWhenShutdownStartsAfterRollback() {
        Job job = job(0, 0);
        failSaves(1);
        afterRollback(() -> given(shutdownSignal.isShuttingDown()).willReturn(true));

        replyService.reply(job.pending());

        assertThat(saves).hasSize(1);

        assertProcessing(job, job.token(), job.startedAt());

        then(aiConverseClient).should().converse(eq(job.pending().aiRequest()), any());
    }

    @Test
    @DisplayName("답변 커밋 직후 결과 확인이 실패해도 이미 저장된 답변을 중복 저장하거나 보상 삭제하지 않는다")
    void preservesCommittedReplyWhenCommitAcknowledgementFails() {
        Job job = job(0, 0);
        failSaves(0);
        AtomicBoolean loseAcknowledgement = new AtomicBoolean(true);
        doAnswer(invocation -> {
            TransactionStatus status = invocation.getArgument(0);
            boolean answerSaved = status.isNewTransaction() && assistantCount(job) > 0
                && loseAcknowledgement.compareAndSet(true, false);
            Object result = invocation.callRealMethod();
            if (answerSaved) {
                throw new TransactionSystemException("AI 답변 커밋 결과를 확인하지 못했습니다.");
            }
            return result;
        }).when(transactionManager).commit(any(TransactionStatus.class));

        replyService.reply(job.pending());

        assertThat(saves).hasSize(1);

        assertThat(outbox(job))
            .hasFieldOrPropertyWithValue("status", ChatbotReplyOutboxStatus.COMPLETED)
            .hasFieldOrPropertyWithValue("attemptCount", FAILURE_COUNT);

        assertThat(messageRepository.existsById(job.messageId())).isTrue();

        assertThat(assistantCount(job)).isEqualTo(1);

        assertThat(redis.opsForValue().get(key(job))).isEqualTo(job.token());

        then(redisMessagePublisher).shouldHaveNoInteractions();

        then(aiConverseClient).should().converse(eq(job.pending().aiRequest()), any());
    }

    private Job job(int startedOffset, int lastMessageOffset) {
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
                CREATED_AT, FAILURE_COUNT, outbox.getId());
            entityManager.refresh(outbox);
            AiConverseRequest request = new AiConverseRequest(null, null, null, null, message.getTurnId(),
                outbox.getTraceId(), null, null, null, List.of(), null);
            PendingAiReply pending = PendingAiReply.of(outbox, token, conversation,
                MessageResponse.of(message, List.of()), request);
            return new Job(outbox.getId(), conversation.getId(), message.getId(), token, startedAt, messageAt, pending);
        });
        lockKeys.add(key(job));
        redis.opsForValue().set(key(job), job.token(), LOCK_TTL);
        AiConverseResponse response = new AiConverseResponse(AiConverseResponse.SUCCESS_CODE,
            job.pending().aiRequest().turnId(), new AiConverseResponse.Data(AiRoute.KNOWLEDGE, null, ANSWER, null));
        given(aiConverseClient.converse(eq(job.pending().aiRequest()), any())).willReturn(response);
        return job;
    }

    private void failSaves(int failureCount) {
        AtomicInteger remaining = new AtomicInteger(failureCount);
        doAnswer(invocation -> {
            Message message = invocation.getArgument(0);
            if (message.getSenderType() == SenderType.ASSISTANT) {
                saves.add(new SaveAttempt(message.getContent(), message.getTurnId()));
                if (remaining.getAndDecrement() > 0) {
                    entityManager.flush();
                    throw new TransientDataAccessResourceException("AI 답변 저장 중 DB 연결이 끊겼습니다.");
                }
            }
            entityManager.persist(message);
            return message;
        }).when(messageRepository).save(any(Message.class));
    }

    private void afterRollback(Runnable callback) {
        doAnswer(invocation -> {
            TransactionStatus status = invocation.getArgument(0);
            boolean newTransaction = status.isNewTransaction();
            Object result = invocation.callRealMethod();
            if (newTransaction) {
                callback.run();
            }
            return result;
        }).when(transactionManager).rollback(any(TransactionStatus.class));
    }

    private void assertProcessing(Job job, String token, LocalDateTime startedAt) {
        assertThat(outbox(job))
            .hasFieldOrPropertyWithValue("status", ChatbotReplyOutboxStatus.PROCESSING)
            .hasFieldOrPropertyWithValue("attemptCount", FAILURE_COUNT)
            .hasFieldOrPropertyWithValue("lockToken", token)
            .hasFieldOrPropertyWithValue("processingStartedAt", startedAt);

        assertThat(messageRepository.existsById(job.messageId())).isTrue();

        assertThat(assistantCount(job)).isZero();

        assertThat(redis.opsForValue().get(key(job))).isEqualTo(token);

        then(redisMessagePublisher).shouldHaveNoInteractions();
    }

    private ChatbotReplyOutbox outbox(Job job) {
        return outboxRepository.findById(job.eventId()).orElseThrow();
    }

    private int assistantCount(Job job) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM Messages WHERE conversation_id = ?"
            + " AND sender_type = 'ASSISTANT'", Integer.class, job.conversationId());
    }

    private ChatbotReplyEvent publishedEvent() {
        ArgumentCaptor<ChatbotReplyEvent> event = ArgumentCaptor.forClass(ChatbotReplyEvent.class);

        then(redisMessagePublisher).should().publish(eq(CHANNEL), event.capture());

        return event.getValue();
    }

    private static String key(Job job) {
        return "conversation:replying:" + job.conversationId();
    }

    private record SaveAttempt(String content, String turnId) {
    }

    private record Job(Long eventId, Long conversationId, Long messageId, String token,
                       LocalDateTime startedAt, LocalDateTime lastMessageAt, PendingAiReply pending) {
    }
}
