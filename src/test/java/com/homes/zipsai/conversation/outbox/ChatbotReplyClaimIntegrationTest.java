package com.homes.zipsai.conversation.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.sql.DataSource;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import com.homes.zipsai.conversation.domain.ChatbotReplyOutbox;
import com.homes.zipsai.conversation.domain.ChatbotReplyOutboxStatus;
import com.homes.zipsai.conversation.domain.Conversation;
import com.homes.zipsai.conversation.domain.ConversationType;
import com.homes.zipsai.conversation.domain.Message;
import com.homes.zipsai.conversation.domain.MessageType;
import com.homes.zipsai.conversation.domain.SenderType;
import com.homes.zipsai.conversation.outbox.ChatbotReplyClaimService.Claim;
import com.homes.zipsai.conversation.repository.ChatbotReplyOutboxRepository;
import com.homes.zipsai.user.domain.User;
import com.homes.zipsai.user.domain.UserRole;

@SpringBootTest
@DirtiesContext
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:tc:mysql:8.4.11:///zipsai_chatbot_reply_claim?TC_DAEMON=true",
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
class ChatbotReplyClaimIntegrationTest {

    private static final Duration LOCK_TTL = Duration.ofSeconds(150);
    private static final GenericContainer<?> REDIS =
        new GenericContainer<>(DockerImageName.parse("redis:8.2.10-alpine")).withExposedPorts(6379);

    @Autowired
    ChatbotReplyClaimService claimService;

    @Autowired
    ChatbotReplyOutboxRepository outboxRepository;

    @Autowired
    EntityManager entityManager;

    @Autowired
    TransactionTemplate transactions;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    DataSource dataSource;

    @Autowired
    StringRedisTemplate redis;

    @MockitoSpyBean(name = "transactionManager")
    PlatformTransactionManager transactionManager;

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
    }

    @AfterEach
    void removeInputLocks() {
        if (!lockKeys.isEmpty()) {
            redis.delete(lockKeys);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("대기 작업은 Redis 키 유무와 관계없이 실패 횟수를 유지하고 새 소유권을 커밋한다")
    void claimsPendingJobAndCommitsOwnership(boolean missingLock) {
        Job job = job("PENDING", 1);
        if (missingLock) {
            redis.delete(key(job));
        }
        LocalDateTime before = databaseTime();
        LocalDateTime previousLastMessageAt = lastMessageAt(job);

        List<Claim> claims = claimService.claimBatch();

        assertThat(claims).extracting(Claim::eventId).containsExactly(job.eventId());

        Claim claim = claims.getFirst();
        assertThat(claim.attempt()).isEqualTo(1);

        assertThat(claim.lockToken()).isNotEqualTo(job.token());

        assertOwnedBy(job, claim, 1);

        assertThat(outbox(job).getProcessingStartedAt()).isBetween(before, databaseTime());

        assertThat(lastMessageAt(job)).isEqualTo(previousLastMessageAt);

        assertThat(redis.getExpire(key(job), TimeUnit.MILLISECONDS)).isPositive().isLessThanOrEqualTo(150_000L);
    }

    @Test
    @DisplayName("130초가 지난 미기록 시도는 실패를 한 번 집계하고 같은 작업을 다시 집계하지 않는다")
    void countsInterruptedAttemptOnce() {
        Job job = job("PROCESSING", 1);

        List<Claim> claims = claimService.claimBatch();

        assertThat(claims).extracting(Claim::eventId).containsExactly(job.eventId());

        assertOwnedBy(job, claims.getFirst(), 2);

        assertThat(claims.getFirst().attempt()).isEqualTo(2);

        assertThat(claimService.claimBatch()).isEmpty();

        assertThat(outbox(job).getAttemptCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("다른 소유자의 작업을 보존하고 뒤의 정상 작업을 최대 10건까지 선점한다")
    void claimsAtMostTenEligibleJobsAfterSkippedCandidates() {
        List<Job> blockedJobs = new ArrayList<>();
        for (int index = 0; index < 10; index++) {
            Job blocked = job("PROCESSING", 1);
            redis.opsForValue().set(key(blocked), "other-token", LOCK_TTL);
            blockedJobs.add(blocked);
        }
        List<Job> jobs = new ArrayList<>();
        for (int index = 0; index < 11; index++) {
            jobs.add(job("PENDING", 0));
        }

        List<Claim> claims = claimService.claimBatch();

        assertThat(claims).hasSize(10);

        assertThat(claims).extracting(Claim::eventId)
            .containsExactlyElementsOf(jobs.subList(0, 10).stream().map(Job::eventId).toList());

        assertThat(outbox(jobs.getLast()).getStatus()).isEqualTo(ChatbotReplyOutboxStatus.PENDING);

        Job blocked = blockedJobs.getFirst();
        assertThat(outbox(blocked).getLockToken()).isEqualTo(blocked.token());

        assertThat(outbox(blocked).getAttemptCount()).isEqualTo(1);

        assertThat(redis.opsForValue().get(key(blocked))).isEqualTo("other-token");
    }

    @Test
    @DisplayName("다른 DB 연결이 잠근 작업은 기다리지 않고 다음 작업을 선점한다")
    void skipsLockedJob() throws Exception {
        Job first = job("PENDING", 0);
        Job second = job("PENDING", 0);
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT event_id FROM Chatbot_reply_outbox WHERE event_id = ? FOR UPDATE
                    """)) {
                statement.setLong(1, first.eventId());
                try (ResultSet result = statement.executeQuery()) {
                    assertThat(result.next()).isTrue();
                }
            }
            try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
                Future<List<Claim>> result = executor.submit(claimService::claimBatch);
                try {
                    assertThat(result.get(5, TimeUnit.SECONDS)).extracting(Claim::eventId)
                        .containsExactly(second.eventId());
                } finally {
                    connection.rollback();
                }
            }
        }

        assertThat(outbox(first).getStatus()).isEqualTo(ChatbotReplyOutboxStatus.PENDING);

        assertThat(redis.opsForValue().get(key(first))).isEqualTo(first.token());
    }

    @Test
    @DisplayName("두 워커가 동시에 조회해도 같은 작업의 소유권은 한 번만 반환한다")
    void claimsOnceAcrossConcurrentWorkers() throws Exception {
        Job job = job("PENDING", 1);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<List<Claim>> first = executor.submit(() -> {
                start.await();
                return claimService.claimBatch();
            });
            Future<List<Claim>> second = executor.submit(() -> {
                start.await();
                return claimService.claimBatch();
            });
            start.countDown();
            List<Claim> claims = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS))
                .stream().flatMap(List::stream).toList();

            assertThat(claims).extracting(Claim::eventId).containsExactly(job.eventId());

            assertOwnedBy(job, claims.getFirst(), 1);
        }
    }

    @Test
    @DisplayName("전체 마감에 도달한 요청은 입력 락을 다시 획득하지 않는다")
    void doesNotClaimExpiredRequest() {
        Job job = job("PENDING", 1);
        jdbc.update("UPDATE Chatbot_reply_outbox SET created_at = ? WHERE event_id = ?",
            databaseTime().minusSeconds(280), job.eventId());
        redis.delete(key(job));

        assertThat(claimService.claimBatch()).isEmpty();

        assertThat(redis.hasKey(key(job))).isFalse();

        assertThat(outbox(job).getStatus()).isEqualTo(ChatbotReplyOutboxStatus.PENDING);

        assertThat(outbox(job).getAttemptCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("새 락의 TTL은 남은 전체 요청 시간을 넘기지 않는다")
    void boundsLockTtlByRequestDeadline() {
        Job job = job("PENDING", 1);
        jdbc.update("UPDATE Chatbot_reply_outbox SET created_at = ? WHERE event_id = ?",
            databaseTime().minusSeconds(260), job.eventId());

        assertThat(claimService.claimBatch()).extracting(Claim::eventId).containsExactly(job.eventId());

        assertThat(redis.getExpire(key(job), TimeUnit.MILLISECONDS)).isPositive()
            .isLessThanOrEqualTo(20_000L);
    }

    @Test
    @DisplayName("더 최근 사용자 요청이 있으면 이전 작업이 빈 Redis 키를 재획득하지 않는다")
    void doesNotReacquireForSupersededRequest() {
        Job job = job("PENDING", 1);
        transactions.executeWithoutResult(transaction -> {
            Conversation conversation = entityManager.find(Conversation.class, job.conversationId());
            entityManager.persist(new Message(conversation, "새 질문", SenderType.RESIDENT, MessageType.TEXT,
                UUID.randomUUID().toString()));
        });
        redis.delete(key(job));

        assertThat(claimService.claimBatch()).isEmpty();

        assertThat(redis.hasKey(key(job))).isFalse();

        assertThat(outbox(job).getLockToken()).isEqualTo(job.token());
    }

    @Test
    @DisplayName("Redis 교체 후 DB 커밋이 롤백되면 발급한 토큰으로 DB를 복구하고 실패를 한 번만 집계한다")
    void repairsDatabaseAfterRolledBackCommit() {
        Job job = job("PROCESSING", 1);
        AtomicBoolean intercepted = new AtomicBoolean();
        doAnswer(invocation -> {
            if (!job.token().equals(redis.opsForValue().get(key(job)))
                    && intercepted.compareAndSet(false, true)) {
                transactionManager.rollback(invocation.getArgument(0, TransactionStatus.class));
                throw new TransactionSystemException("DB commit failed before completion");
            }
            return invocation.callRealMethod();
        }).when(transactionManager).commit(any());

        List<Claim> claims = claimService.claimBatch();

        assertThat(claims).extracting(Claim::eventId).containsExactly(job.eventId());

        assertOwnedBy(job, claims.getFirst(), 2);

        assertThat(claims.getFirst().lockToken()).isNotEqualTo(job.token());
    }

    private Job job(String status, int failures) {
        LocalDateTime startedAt = databaseTime().minusSeconds(131);
        Job job = transactions.execute(transaction -> {
            User user = new User(UUID.randomUUID() + "@example.com", "password", "입주민", null);
            user.selectRole(UserRole.RESIDENT);
            entityManager.persist(user);
            Conversation conversation = new Conversation(user, ConversationType.INQUIRY, "보일러 문의");
            conversation.updateLastMessageAt(startedAt.minusSeconds(9));
            entityManager.persist(conversation);
            Message message = new Message(conversation, "보일러가 안 켜져요", SenderType.RESIDENT,
                MessageType.TEXT, UUID.randomUUID().toString());
            entityManager.persist(message);
            String token = UUID.randomUUID().toString();
            ChatbotReplyOutbox outbox = ChatbotReplyOutbox.startNextMessage(message, user.getId(),
                UUID.randomUUID().toString(), token, null, startedAt);
            entityManager.persist(outbox);
            entityManager.flush();
            jdbc.update("""
                UPDATE Chatbot_reply_outbox SET status = ?, attempt_count = ?, created_at = ? WHERE event_id = ?
                """, status, failures, startedAt.minusSeconds(9), outbox.getId());
            return new Job(outbox.getId(), conversation.getId(), token);
        });
        lockKeys.add(key(job));
        redis.opsForValue().set(key(job), job.token(), LOCK_TTL);
        return job;
    }

    private void assertOwnedBy(Job job, Claim claim, int failures) {
        ChatbotReplyOutbox saved = outbox(job);
        assertThat(saved.getStatus()).isEqualTo(ChatbotReplyOutboxStatus.PROCESSING);

        assertThat(saved.getAttemptCount()).isEqualTo(failures);

        assertThat(saved.getLockToken()).isEqualTo(claim.lockToken());

        assertThat(redis.opsForValue().get(key(job))).isEqualTo(claim.lockToken());
    }

    private ChatbotReplyOutbox outbox(Job job) {
        return outboxRepository.findById(job.eventId()).orElseThrow();
    }

    private LocalDateTime databaseTime() {
        return jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(6)", LocalDateTime.class);
    }

    private LocalDateTime lastMessageAt(Job job) {
        return jdbc.queryForObject("SELECT last_message_at FROM Conversations WHERE conversation_id = ?",
            LocalDateTime.class, job.conversationId());
    }

    private static String key(Job job) {
        return "conversation:replying:" + job.conversationId();
    }

    private record Job(Long eventId, Long conversationId, String token) {
    }
}
