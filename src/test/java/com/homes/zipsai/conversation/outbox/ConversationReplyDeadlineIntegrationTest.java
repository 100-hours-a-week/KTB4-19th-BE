package com.homes.zipsai.conversation.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.doReturn;

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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
import com.homes.zipsai.global.exception.ApiException;
import com.homes.zipsai.global.logging.StructuredLogger;
import com.homes.zipsai.user.domain.User;
import com.homes.zipsai.user.domain.UserRole;

@SpringBootTest
@DirtiesContext
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:tc:mysql:8.4.11:///zipsai_chatbot_reply_deadline?TC_DAEMON=true",
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
class ConversationReplyDeadlineIntegrationTest {

    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 10, 26, 12, 0);
    private static final Duration LOCK_TTL = Duration.ofSeconds(150);
    private static final String CHANNEL = "test:chatbot-reply-deadline";
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
            new ChatbotReplyPubSubProperties(CHANNEL), replyLock, new ChatbotReplyShutdownSignal(), structuredLogger);
    }

    @AfterEach
    void removeInputLocks() {
        if (!lockKeys.isEmpty()) {
            redis.delete(lockKeys);
        }
        MDC.clear();
    }

    @ParameterizedTest
    @CsvSource({"0, 0, 130", "200, 0, 280", "0, -200, 100"})
    @DisplayName("시도·전체 요청·대화 종료 마감 직전에는 AI 답변을 저장하고 완료를 알린다")
    void completesReplyBeforeDeadline(int startedOffset, int lastMessageOffset, int deadlineOffset) {
        Job job = job(startedOffset, lastMessageOffset);
        checkedAt(CREATED_AT.plusSeconds(deadlineOffset).minusNanos(1000));
        given(aiConverseClient.converse(eq(job.pending().aiRequest()), any())).willReturn(answer(job));

        replyService.reply(job.pending());

        assertThat(outbox(job).getStatus()).isEqualTo(ChatbotReplyOutboxStatus.COMPLETED);

        assertThat(messageRepository.existsById(job.messageId())).isTrue();

        assertThat(assistantCount(job)).isEqualTo(1);

        assertThat(redis.hasKey(key(job))).isFalse();

        assertThat(publishedEvent().eventType()).isEqualTo(ChatbotReplyEvent.Type.DONE);
    }

    @ParameterizedTest
    @CsvSource({"0, 0, 130", "0, 0, 131", "200, 0, 280", "200, 0, 281",
        "0, -200, 100", "0, -200, 101"})
    @DisplayName("시도·전체 요청·대화 종료 마감 정각부터는 AI를 호출하지 않고 최종 실패로 정리한다")
    void failsWithoutCallingAiAtOrAfterDeadline(int startedOffset, int lastMessageOffset, int nowOffset) {
        Job job = job(startedOffset, lastMessageOffset);
        checkedAt(CREATED_AT.plusSeconds(nowOffset));

        replyService.reply(job.pending());

        then(aiConverseClient).shouldHaveNoInteractions();

        assertFailed(job);

        assertThat(redis.hasKey(key(job))).isFalse();
    }

    @ParameterizedTest
    @CsvSource({"0, 0, 130", "200, 0, 280", "0, -200, 100"})
    @DisplayName("AI 호출 중 마감에 도달하면 늦은 답변으로 메시지와 대화 상태를 갱신하지 않는다")
    void rejectsAnswerArrivingAtDeadline(int startedOffset, int lastMessageOffset, int deadlineOffset) {
        Job job = job(startedOffset, lastMessageOffset);
        checkedAt(CREATED_AT.plusSeconds(deadlineOffset).minusNanos(1000));
        given(aiConverseClient.converse(eq(job.pending().aiRequest()), any())).willAnswer(invocation -> {
            checkedAt(CREATED_AT.plusSeconds(deadlineOffset));
            return answer(job);
        });

        replyService.reply(job.pending());

        assertFailed(job);

        assertThat(jdbc.queryForObject("SELECT current_route FROM Conversations WHERE conversation_id = ?",
            String.class, job.conversationId())).isNull();

        assertThat(jdbc.queryForObject("SELECT last_message_at FROM Conversations WHERE conversation_id = ?",
            LocalDateTime.class, job.conversationId())).isEqualTo(job.previousLastMessageAt());
    }

    @ParameterizedTest
    @CsvSource({"0, 0, 130", "200, 0, 280", "0, -200, 100"})
    @DisplayName("마감된 요청 재구성은 소유권 상실의 빈 결과와 구분되는 오류로 전달한다")
    void rejectsPreparingExpiredReply(int startedOffset, int lastMessageOffset, int deadlineOffset) {
        Job job = job(startedOffset, lastMessageOffset);
        checkedAt(CREATED_AT.plusSeconds(deadlineOffset));

        assertThatThrownBy(() -> conversationService.prepareReply(job.eventId(), 0, job.token()))
            .isInstanceOf(ApiException.class);

        assertThat(outbox(job).getStatus()).isEqualTo(ChatbotReplyOutboxStatus.PROCESSING);

        assertThat(messageRepository.existsById(job.messageId())).isTrue();
    }

    @Test
    @DisplayName("접수 확인 대기의 5분 종료 제외는 유지하고 전체 요청 마감 전 답변은 저장한다")
    void keepsConfirmationWaitExcludedFromIdleDeadline() {
        Job job = job(200, -200);
        jdbc.update("UPDATE Conversations SET ai_conversation_state = 'READY_TO_CONFIRM' WHERE conversation_id = ?",
            job.conversationId());
        checkedAt(CREATED_AT.plusSeconds(279));
        given(aiConverseClient.converse(eq(job.pending().aiRequest()), any())).willReturn(answer(job));

        replyService.reply(job.pending());

        assertThat(outbox(job).getStatus()).isEqualTo(ChatbotReplyOutboxStatus.COMPLETED);

        assertThat(assistantCount(job)).isEqualTo(1);

        assertThat(publishedEvent().eventType()).isEqualTo(ChatbotReplyEvent.Type.DONE);
    }

    @Test
    @DisplayName("이미 종료된 대화는 처리 시간이 남아도 AI를 호출하지 않고 실패 정리한다")
    void rejectsClosedConversationBeforeTimeout() {
        Job job = job(0, 0);
        jdbc.update("UPDATE Conversations SET conversation_status = 'CLOSED' WHERE conversation_id = ?",
            job.conversationId());
        checkedAt(CREATED_AT.plusSeconds(10));

        replyService.reply(job.pending());

        then(aiConverseClient).shouldHaveNoInteractions();

        assertFailed(job);
    }

    @ParameterizedTest
    @CsvSource({"PENDING, OWN", "PENDING, ABSENT", "PROCESSING, ABSENT", "PENDING, OTHER", "PROCESSING, OTHER"})
    @DisplayName("전체 마감 후 미완료 작업은 Redis 키가 없어도 정리하고 다른 실행의 락은 보존한다")
    void cleansExpiredJobWithoutDeletingAnotherLock(String status, String lockOwner) {
        Job job = job(200, 0);
        jdbc.update("UPDATE Chatbot_reply_outbox SET status = ? WHERE event_id = ?", status, job.eventId());
        if ("ABSENT".equals(lockOwner)) {
            redis.delete(key(job));
        } else if ("OTHER".equals(lockOwner)) {
            redis.opsForValue().set(key(job), "other-token", LOCK_TTL);
        }
        checkedAt(CREATED_AT.plusSeconds(280));

        replyService.failReply(job.eventId(), 0, job.token());

        assertFailed(job);

        assertThat(redis.opsForValue().get(key(job)))
            .isEqualTo("OTHER".equals(lockOwner) ? "other-token" : null);

        assertThat(outbox(job).getAttemptCount()).isZero();
    }

    @Test
    @DisplayName("전체 마감 전의 재시도 대기 작업은 만료 정리로 실패시키지 않는다")
    void keepsPendingJobBeforeDeadline() {
        Job job = job(200, 0);
        jdbc.update("UPDATE Chatbot_reply_outbox SET status = 'PENDING' WHERE event_id = ?", job.eventId());
        checkedAt(CREATED_AT.plusSeconds(279));

        replyService.failReply(job.eventId(), 0, job.token());

        assertThat(outbox(job).getStatus()).isEqualTo(ChatbotReplyOutboxStatus.PENDING);

        assertThat(messageRepository.existsById(job.messageId())).isTrue();

        assertThat(redis.opsForValue().get(key(job))).isEqualTo(job.token());

        then(redisMessagePublisher).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("마감에 도달했어도 인계 전 토큰의 실행은 인계받은 실행의 메시지·락·알림을 변경하지 않는다")
    void skipsExpiredExecutionAfterOwnershipTransfer() {
        Job job = job(0, 0);
        jdbc.update("UPDATE Chatbot_reply_outbox SET lock_token = ?, processing_started_at = ? WHERE event_id = ?",
            "next-token", CREATED_AT.plusSeconds(120), job.eventId());
        redis.opsForValue().set(key(job), "next-token", LOCK_TTL);
        checkedAt(CREATED_AT.plusSeconds(280));

        replyService.reply(job.pending());

        then(aiConverseClient).shouldHaveNoInteractions();

        then(redisMessagePublisher).shouldHaveNoInteractions();

        assertThat(outbox(job).getStatus()).isEqualTo(ChatbotReplyOutboxStatus.PROCESSING);

        assertThat(messageRepository.existsById(job.messageId())).isTrue();

        assertThat(redis.opsForValue().get(key(job))).isEqualTo("next-token");
    }

    private Job job(int startedOffset, int lastMessageOffset) {
        Job job = transactions.execute(transaction -> {
            User user = new User(UUID.randomUUID() + "@example.com", "password", "입주민", null);
            user.selectRole(UserRole.RESIDENT);
            entityManager.persist(user);
            Conversation conversation = new Conversation(user, ConversationType.INQUIRY, "보일러 문의");
            LocalDateTime messageAt = CREATED_AT.plusSeconds(lastMessageOffset);
            LocalDateTime previousLastMessageAt = messageAt.minusSeconds(10);
            conversation.updateLastMessageAt(messageAt);
            entityManager.persist(conversation);
            Message message = new Message(conversation, "보일러가 안 켜져요", SenderType.RESIDENT,
                MessageType.TEXT, UUID.randomUUID().toString());
            entityManager.persist(message);
            String token = UUID.randomUUID().toString();
            ChatbotReplyOutbox outbox = ChatbotReplyOutbox.startNextMessage(message, user.getId(),
                UUID.randomUUID().toString(), token, previousLastMessageAt, CREATED_AT.plusSeconds(startedOffset));
            entityManager.persist(outbox);
            entityManager.flush();
            jdbc.update("UPDATE Chatbot_reply_outbox SET created_at = ? WHERE event_id = ?", CREATED_AT,
                outbox.getId());
            jdbc.update("UPDATE Messages SET created_at = ? WHERE message_id = ?", messageAt, message.getId());
            AiConverseRequest request = new AiConverseRequest(null, null, null, null, message.getTurnId(),
                outbox.getTraceId(), null, null, null, List.of(), null);
            PendingAiReply pending = PendingAiReply.of(outbox, token, conversation,
                MessageResponse.of(message, List.of()), request);
            return new Job(outbox.getId(), conversation.getId(), message.getId(), token, previousLastMessageAt,
                pending);
        });
        lockKeys.add(key(job));
        redis.opsForValue().set(key(job), job.token(), LOCK_TTL);
        return job;
    }

    private void checkedAt(LocalDateTime now) {
        doReturn(now).when(outboxRepository).currentTime();
    }

    private void assertFailed(Job job) {
        assertThat(outbox(job).getStatus()).isEqualTo(ChatbotReplyOutboxStatus.FAILED);

        assertThat(messageRepository.existsById(job.messageId())).isFalse();

        assertThat(assistantCount(job)).isZero();

        assertThat(publishedEvent().eventType()).isEqualTo(ChatbotReplyEvent.Type.FAILED);
    }

    private ChatbotReplyOutbox outbox(Job job) {
        return outboxRepository.findById(job.eventId()).orElseThrow();
    }

    private int assistantCount(Job job) {
        return jdbc.queryForObject("""
            SELECT COUNT(*) FROM Messages WHERE conversation_id = ? AND sender_type = 'ASSISTANT'
            """, Integer.class, job.conversationId());
    }

    private ChatbotReplyEvent publishedEvent() {
        ArgumentCaptor<ChatbotReplyEvent> event = ArgumentCaptor.forClass(ChatbotReplyEvent.class);

        then(redisMessagePublisher).should().publish(eq(CHANNEL), event.capture());

        return event.getValue();
    }

    private static AiConverseResponse answer(Job job) {
        return new AiConverseResponse(AiConverseResponse.SUCCESS_CODE, job.pending().aiRequest().turnId(),
            new AiConverseResponse.Data(AiRoute.KNOWLEDGE, null, "관리실에 문의해 주세요.",
                AiConverseResponse.Result.builder().citations(List.of()).build()));
    }

    private static String key(Job job) {
        return "conversation:replying:" + job.conversationId();
    }

    private record Job(Long eventId, Long conversationId, Long messageId, String token,
                       LocalDateTime previousLastMessageAt, PendingAiReply pending) {
    }
}
