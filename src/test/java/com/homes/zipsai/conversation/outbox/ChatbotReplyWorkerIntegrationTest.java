package com.homes.zipsai.conversation.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import com.homes.zipsai.building.domain.Building;
import com.homes.zipsai.building.domain.Room;
import com.homes.zipsai.building.service.ResidentRoomService;
import com.homes.zipsai.common.service.RedisMessagePublisher;
import com.homes.zipsai.conversation.ai.AiConverseClient;
import com.homes.zipsai.conversation.ai.AiConverseRequest;
import com.homes.zipsai.conversation.ai.AiConverseResponse;
import com.homes.zipsai.conversation.ai.AiRoute;
import com.homes.zipsai.conversation.ai.error.AiConverseErrorType;
import com.homes.zipsai.conversation.ai.error.AiConverseException;
import com.homes.zipsai.conversation.domain.ChatbotReplyOutbox;
import com.homes.zipsai.conversation.domain.ChatbotReplyOutboxStatus;
import com.homes.zipsai.conversation.domain.Conversation;
import com.homes.zipsai.conversation.domain.ConversationType;
import com.homes.zipsai.conversation.domain.Message;
import com.homes.zipsai.conversation.domain.MessageType;
import com.homes.zipsai.conversation.domain.SenderType;
import com.homes.zipsai.conversation.event.ChatbotReplyEvent;
import com.homes.zipsai.conversation.repository.ChatbotReplyOutboxRepository;
import com.homes.zipsai.conversation.repository.MessageRepository;
import com.homes.zipsai.conversation.service.ConversationReplyService;
import com.homes.zipsai.conversation.service.ConversationService;
import com.homes.zipsai.global.config.AsyncConfig;
import com.homes.zipsai.global.exception.AiUnavailableException;
import com.homes.zipsai.user.domain.User;
import com.homes.zipsai.user.domain.UserRole;

@SpringBootTest
@DirtiesContext
@Timeout(30)
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:tc:mysql:8.4.11:///zipsai_chatbot_reply_worker",
    "spring.datasource.username=test",
    "spring.datasource.password=test",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.jpa.hibernate.naming.physical-strategy=org.hibernate.boot.model.naming.PhysicalNamingStrategyStandardImpl",
    "spring.jpa.defer-datasource-initialization=false",
    "spring.flyway.enabled=true",
    "app.conversation.reply-worker.enabled=false",
    "app.conversation.reply-worker.attempt-timeout=130s",
    "app.conversation.reply-worker.request-timeout=280s",
    "app.conversation.reply-lock.ttl=150s"
})
class ChatbotReplyWorkerIntegrationTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 27, 12, 0);
    private static final Duration LOCK_TTL = Duration.ofSeconds(150);
    private static final GenericContainer<?> REDIS =
        new GenericContainer<>(DockerImageName.parse("redis:8.2.10-alpine")).withExposedPorts(6379);

    @Autowired
    ChatbotReplyWorker worker;

    @Autowired
    ConversationReplyService replyService;

    @Autowired
    EntityManager entityManager;

    @Autowired
    TransactionTemplate transactions;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    MessageRepository messageRepository;

    @MockitoSpyBean
    ChatbotReplyOutboxRepository outboxRepository;

    @MockitoSpyBean
    ConversationService conversationService;

    @MockitoBean
    AiConverseClient aiConverseClient;

    @MockitoBean
    ResidentRoomService residentRoomService;

    @MockitoBean
    RedisMessagePublisher redisMessagePublisher;

    @MockitoBean(name = AsyncConfig.CHATBOT_REPLY_EXECUTOR)
    TaskExecutor replyExecutor;

    private final List<String> lockKeys = new ArrayList<>();

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
        doReturn(NOW).when(outboxRepository).currentTime();
        doAnswer(invocation -> {
            invocation.getArgument(0, Runnable.class).run();
            return null;
        }).when(replyExecutor).execute(any(Runnable.class));
        given(aiConverseClient.converse(any(), any())).willAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            AiConverseRequest request = invocation.getArgument(0);
            return response(request);
        });
    }

    @AfterEach
    void removeInputLocks() {
        if (!lockKeys.isEmpty()) {
            redis.delete(lockKeys);
        }
        MDC.clear();
    }

    @Test
    @DisplayName("최초 AI 실패로 대기 중인 작업은 다음 스캔에서 같은 요청으로 다시 실행한다")
    void retriesPendingReplyFromOriginalRequest() {
        Job job = job("PROCESSING", 0);
        AtomicBoolean firstCall = new AtomicBoolean(true);
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            if (firstCall.getAndSet(false)) {
                throw new AiConverseException(AiConverseErrorType.HTTP_ERROR, new AiUnavailableException(), null);
            }
            return response(invocation.getArgument(0));
        }).when(aiConverseClient).converse(any(), any());

        replyService.reply(conversationService.prepareReply(job.eventId(), 0, job.token()).orElseThrow());

        assertState(job, ChatbotReplyOutboxStatus.PENDING, 1);

        assertThat(redis.opsForValue().get(key(job))).isEqualTo(job.token());

        worker.processBatch();

        assertCompleted(job, 1);

        assertThat(outbox(job).getLockToken()).isNotEqualTo(job.token());

        ArgumentCaptor<AiConverseRequest> requests = ArgumentCaptor.forClass(AiConverseRequest.class);
        then(aiConverseClient).should(times(2)).converse(requests.capture(), any());

        assertThat(requests.getAllValues()).allSatisfy(request -> {
            assertThat(request.turnId()).isEqualTo(job.turnId());

            assertThat(request.traceId()).isEqualTo(job.traceId());

            assertThat(request.message().messageId()).isEqualTo(job.messageId().toString());
        });

        then(redisMessagePublisher).should().publish(any(), any(ChatbotReplyEvent.class));
    }

    @Test
    @DisplayName("130초가 지난 처리 중 작업은 실패를 한 번 집계하고 다시 실행한다")
    void recoversInterruptedReply() {
        Job job = job("PROCESSING", 1);
        jdbc.update("UPDATE Chatbot_reply_outbox SET processing_started_at = ? WHERE event_id = ?",
            NOW.minusSeconds(131), job.eventId());

        worker.processBatch();

        assertCompleted(job, 2);

        assertThat(outbox(job).getLockToken()).isNotEqualTo(job.token());

        assertThat(outbox(job).getProcessingStartedAt()).isEqualTo(NOW);

        then(aiConverseClient).should().converse(any(), any());
    }

    @Test
    @DisplayName("중단 복구로 누적 실패가 3회가 되면 AI를 호출하지 않고 최종 실패한다")
    void failsInterruptedReplyAtFailureLimit() {
        Job job = job("PROCESSING", 2);
        jdbc.update("UPDATE Chatbot_reply_outbox SET processing_started_at = ? WHERE event_id = ?",
            NOW.minusSeconds(131), job.eventId());

        worker.processBatch();

        assertFailed(job, 3);

        assertThat(redis.hasKey(key(job))).isFalse();

        then(aiConverseClient).shouldHaveNoInteractions();

        assertFailureEvent(job);
    }

    @ParameterizedTest
    @CsvSource({
        "PENDING, false, own",
        "PROCESSING, true, other"
    })
    @DisplayName("전체 마감이나 대화 종료에 도달한 미완료 작업은 AI 호출 없이 정리하고 다른 입력 락은 보존한다")
    void cleansUpExpiredReply(String status, boolean conversationClosed, String owner) {
        Job job = job(status, 1);
        if (conversationClosed) {
            jdbc.update("UPDATE Conversations SET last_message_at = ? WHERE conversation_id = ?",
                NOW.minusMinutes(5), job.conversationId());
        } else {
            jdbc.update("UPDATE Chatbot_reply_outbox SET created_at = ? WHERE event_id = ?",
                NOW.minusSeconds(280), job.eventId());
        }
        if ("other".equals(owner)) {
            redis.opsForValue().set(key(job), "other-token", LOCK_TTL);
        }

        worker.processBatch();
        worker.processBatch();

        assertFailed(job, 1);

        assertThat(redis.opsForValue().get(key(job))).isEqualTo("other".equals(owner) ? "other-token" : null);

        then(aiConverseClient).shouldHaveNoInteractions();

        assertFailureEvent(job);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PREPARE", "SUBMIT"})
    @DisplayName("요청 준비나 비동기 제출에 실패하면 횟수와 입력 차단을 유지하고 다른 작업과 다음 스캔을 진행한다")
    void retriesBackendFailureWithoutIncreasingCount(String failureAt) {
        Job firstJob = job("PENDING", 1);
        Job secondJob = job("PENDING", 0);
        List<Job> jobs = List.of(firstJob, secondJob);
        Map<Long, Integer> failureCounts = Map.of(firstJob.eventId(), 1, secondJob.eventId(), 0);
        if ("PREPARE".equals(failureAt)) {
            given(residentRoomService.getLivingRoom(firstJob.userId()))
                .willThrow(new CannotAcquireLockException("입주 정보 조회 중 DB 잠금 대기에 실패했습니다."))
                .willReturn(firstJob.room());
        } else {
            AtomicBoolean rejectFirst = new AtomicBoolean(true);
            doAnswer(invocation -> {
                if (rejectFirst.getAndSet(false)) {
                    throw new TaskRejectedException("챗봇 비동기 작업 제출이 거절됐습니다.");
                }
                invocation.getArgument(0, Runnable.class).run();
                return null;
            }).when(replyExecutor).execute(any(Runnable.class));
        }

        worker.processBatch();

        List<Job> pending = jobs.stream()
            .filter(job -> outbox(job).getStatus() == ChatbotReplyOutboxStatus.PENDING).toList();
        assertThat(pending).hasSize(1);

        Job waiting = pending.getFirst();
        Job completed = jobs.stream().filter(job -> !job.equals(waiting)).findFirst().orElseThrow();
        assertState(waiting, ChatbotReplyOutboxStatus.PENDING, failureCounts.get(waiting.eventId()));

        assertThat(messageRepository.existsById(waiting.messageId())).isTrue();

        assertThat(redis.opsForValue().get(key(waiting))).isEqualTo(outbox(waiting).getLockToken());

        assertCompleted(completed, failureCounts.get(completed.eventId()));

        then(aiConverseClient).should().converse(any(), any());

        worker.processBatch();

        assertThat(jobs).allSatisfy(job -> assertCompleted(job, failureCounts.get(job.eventId())));

        ArgumentCaptor<AiConverseRequest> requests = ArgumentCaptor.forClass(AiConverseRequest.class);
        then(aiConverseClient).should(times(2)).converse(requests.capture(), any());

        assertThat(requests.getAllValues()).extracting(AiConverseRequest::turnId)
            .containsExactlyInAnyOrder(firstJob.turnId(), secondJob.turnId());
    }

    @Test
    @DisplayName("한 스캔에서는 최대 10건만 실행하고 남은 작업은 다음 스캔으로 넘긴다")
    void executesAtMostTenRepliesPerScan() {
        List<Job> jobs = new ArrayList<>();
        for (int index = 0; index < 11; index++) {
            jobs.add(job("PENDING", 0));
        }

        worker.processBatch();

        List<ChatbotReplyOutboxStatus> statuses = jobs.stream().map(this::outbox)
            .map(ChatbotReplyOutbox::getStatus).toList();
        assertThat(statuses).filteredOn(ChatbotReplyOutboxStatus.COMPLETED::equals).hasSize(10);

        assertThat(statuses).filteredOn(ChatbotReplyOutboxStatus.PENDING::equals).hasSize(1);

        then(aiConverseClient).should(times(10)).converse(any(), any());

        worker.processBatch();

        assertThat(jobs.stream().map(this::outbox)).extracting(ChatbotReplyOutbox::getStatus)
            .containsOnly(ChatbotReplyOutboxStatus.COMPLETED);

        then(aiConverseClient).should(times(11)).converse(any(), any());
    }

    @Test
    @DisplayName("만료 작업의 실패 정리가 DB 오류로 실패해도 다른 작업을 실행하고 다음 스캔에서 정리한다")
    void retriesCleanupAfterDatabaseRecovery() {
        Job expired = job("PENDING", 1);
        jdbc.update("UPDATE Chatbot_reply_outbox SET created_at = ? WHERE event_id = ?",
            NOW.minusSeconds(280), expired.eventId());
        Job healthy = job("PENDING", 0);
        doThrow(new CannotAcquireLockException("최종 실패 기록 중 DB 잠금 대기에 실패했습니다."))
            .doCallRealMethod().when(conversationService)
            .discardUnansweredMessage(expired.eventId(), 1, expired.token());

        worker.processBatch();

        assertState(expired, ChatbotReplyOutboxStatus.PENDING, 1);

        assertThat(messageRepository.existsById(expired.messageId())).isTrue();

        assertCompleted(healthy, 0);

        worker.processBatch();

        assertFailed(expired, 1);

        assertThat(redis.hasKey(key(expired))).isFalse();

        then(aiConverseClient).should().converse(any(), any());
    }

    private Job job(String status, int failureCount) {
        Job job = transactions.execute(transaction -> {
            User user = new User(UUID.randomUUID() + "@example.com", "password", "입주민", null);
            user.selectRole(UserRole.RESIDENT);
            entityManager.persist(user);
            Conversation conversation = new Conversation(user, ConversationType.INQUIRY, "보일러 문의");
            conversation.updateLastMessageAt(NOW.minusSeconds(10));
            entityManager.persist(conversation);
            Message message = new Message(conversation, "보일러가 안 켜져요", SenderType.RESIDENT,
                MessageType.TEXT, UUID.randomUUID().toString());
            entityManager.persist(message);
            String token = UUID.randomUUID().toString();
            ChatbotReplyOutbox outbox = ChatbotReplyOutbox.startNextMessage(message, user.getId(),
                UUID.randomUUID().toString(), token, NOW.minusSeconds(20), NOW.minusSeconds(5));
            if ("PENDING".equals(status)) {
                outbox.retry(failureCount);
            }
            entityManager.persist(outbox);
            entityManager.flush();
            jdbc.update("UPDATE Chatbot_reply_outbox SET created_at = ?, attempt_count = ? WHERE event_id = ?",
                NOW.minusSeconds(140), failureCount, outbox.getId());
            Building building = mock(Building.class);
            given(building.getId()).willReturn(77L);
            Room room = new Room(building, "101");
            room.invite();
            room.moveIn(user);
            return new Job(outbox.getId(), conversation.getId(), message.getId(), user.getId(), token,
                message.getTurnId(), outbox.getTraceId(), room);
        });
        lockKeys.add(key(job));
        redis.opsForValue().set(key(job), job.token(), LOCK_TTL);
        given(residentRoomService.getLivingRoom(job.userId())).willReturn(job.room());
        return job;
    }

    private ChatbotReplyOutbox outbox(Job job) {
        return outboxRepository.findById(job.eventId()).orElseThrow();
    }

    private void assertState(Job job, ChatbotReplyOutboxStatus status, int failureCount) {
        assertThat(outbox(job))
            .hasFieldOrPropertyWithValue("status", status)
            .hasFieldOrPropertyWithValue("attemptCount", failureCount);
    }

    private void assertCompleted(Job job, int failureCount) {
        assertState(job, ChatbotReplyOutboxStatus.COMPLETED, failureCount);

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM Messages
                WHERE conversation_id = ? AND sender_type = 'ASSISTANT' AND turn_id = ?
                """, Long.class, job.conversationId(), job.turnId())).isEqualTo(1L);

        assertThat(messageRepository.existsById(job.messageId())).isTrue();

        assertThat(redis.hasKey(key(job))).isFalse();
    }

    private void assertFailed(Job job, int failureCount) {
        assertState(job, ChatbotReplyOutboxStatus.FAILED, failureCount);

        assertThat(messageRepository.existsById(job.messageId())).isFalse();

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM Messages WHERE conversation_id = ? AND sender_type = 'ASSISTANT'
                """, Long.class, job.conversationId())).isZero();
    }

    private void assertFailureEvent(Job job) {
        ArgumentCaptor<ChatbotReplyEvent> events = ArgumentCaptor.forClass(ChatbotReplyEvent.class);
        then(redisMessagePublisher).should().publish(any(), events.capture());

        assertThat(events.getValue().eventType()).isEqualTo(ChatbotReplyEvent.Type.FAILED);

        assertThat(events.getValue().userId()).isEqualTo(job.userId());

        assertThat(events.getValue().failure().conversationId()).isEqualTo(job.conversationId());
    }

    private static AiConverseResponse response(AiConverseRequest request) {
        return new AiConverseResponse(AiConverseResponse.SUCCESS_CODE, request.turnId(),
            new AiConverseResponse.Data(AiRoute.KNOWLEDGE, null, "관리실에 보일러 점검을 요청해 주세요.", null));
    }

    private static String key(Job job) {
        return "conversation:replying:" + job.conversationId();
    }

    private record Job(Long eventId, Long conversationId, Long messageId, Long userId, String token, String turnId,
                       String traceId, Room room) {
    }
}
