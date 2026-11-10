package com.homes.zipsai.conversation.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.test.JobOperatorTestUtils;
import org.springframework.batch.test.context.SpringBatchTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import com.homes.zipsai.conversation.domain.ChatbotReplyOutbox;
import com.homes.zipsai.conversation.domain.Conversation;
import com.homes.zipsai.conversation.domain.ConversationType;
import com.homes.zipsai.conversation.domain.Message;
import com.homes.zipsai.conversation.domain.MessageType;
import com.homes.zipsai.conversation.domain.SenderType;
import com.homes.zipsai.user.domain.User;
import com.homes.zipsai.user.domain.UserRole;

@SpringBootTest
@SpringBatchTest
@DirtiesContext
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:tc:mysql:8.4.11:///zipsai_chatbot_reply_outbox_cleanup?TC_DAEMON=true",
    "spring.datasource.username=test",
    "spring.datasource.password=test",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.jpa.hibernate.naming.physical-strategy=org.hibernate.boot.model.naming.PhysicalNamingStrategyStandardImpl",
    "spring.jpa.defer-datasource-initialization=false",
    "spring.flyway.enabled=true",
    "app.conversation.reply-outbox-cleanup.batch-size=2"
})
class ChatbotReplyOutboxCleanupJobIntegrationTest {

    private static final GenericContainer<?> REDIS =
        new GenericContainer<>(DockerImageName.parse("redis:8.2.10-alpine")).withExposedPorts(6379);

    @Autowired
    JobOperatorTestUtils jobOperatorTestUtils;

    @Autowired
    @Qualifier("chatbotReplyOutboxCleanupJob")
    Job chatbotReplyOutboxCleanupJob;

    @Autowired
    EntityManager entityManager;

    @Autowired
    TransactionTemplate transactions;

    @Autowired
    JdbcTemplate jdbc;

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        REDIS.start();
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @AfterAll
    static void stopContainers() {
        REDIS.stop();
    }

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM Chatbot_reply_outbox");
        jobOperatorTestUtils.setJob(chatbotReplyOutboxCleanupJob);
    }

    @Test
    @DisplayName("완료 7일·실패 30일이 지난 요청만 지우고 보관 기간 안의 요청과 진행 중인 요청은 남긴다")
    void deletesOnlyExpiredFinishedRequests() throws Exception {
        Long expiredCompleted = outbox("COMPLETED", 7 * 24 * 60 + 1);
        Long keptCompleted = outbox("COMPLETED", 7 * 24 * 60 - 1);
        Long expiredFailed = outbox("FAILED", 30 * 24 * 60 + 1);
        Long keptFailed = outbox("FAILED", 8 * 24 * 60);
        Long oldPending = outbox("PENDING", 60 * 24 * 60);
        Long oldProcessing = outbox("PROCESSING", 60 * 24 * 60);

        JobExecution execution =
            jobOperatorTestUtils.startJob(jobOperatorTestUtils.getUniqueJobParametersBuilder().toJobParameters());

        assertThat(execution.getExitStatus()).isEqualTo(ExitStatus.COMPLETED);

        assertThat(remainingIds()).containsExactlyInAnyOrder(keptCompleted, keptFailed, oldPending, oldProcessing);

        assertThat(remainingIds()).doesNotContain(expiredCompleted, expiredFailed);
    }

    @Test
    @DisplayName("지울 요청이 한 번에 지우는 건수보다 많으면 나눠서 모두 지우고 지운 건수를 단계별로 남긴다")
    void deletesExpiredRequestsInBatches() throws Exception {
        for (int index = 0; index < 5; index++) {
            outbox("COMPLETED", 8 * 24 * 60);
        }
        outbox("FAILED", 31 * 24 * 60);

        JobExecution execution =
            jobOperatorTestUtils.startJob(jobOperatorTestUtils.getUniqueJobParametersBuilder().toJobParameters());

        assertThat(remainingIds()).isEmpty();

        assertThat(execution.getStepExecutions()).extracting(StepExecution::getStepName, StepExecution::getWriteCount)
            .containsExactly(tuple("chatbotReplyOutboxCompletedCleanupStep", 5L),
                tuple("chatbotReplyOutboxFailedCleanupStep", 1L));
    }

    @Test
    @DisplayName("지울 요청이 없으면 아무것도 지우지 않고 정상 종료한다")
    void finishesWithoutExpiredRequests() throws Exception {
        Long recent = outbox("COMPLETED", 60);

        JobExecution execution =
            jobOperatorTestUtils.startJob(jobOperatorTestUtils.getUniqueJobParametersBuilder().toJobParameters());

        assertThat(execution.getExitStatus()).isEqualTo(ExitStatus.COMPLETED);

        assertThat(remainingIds()).containsExactly(recent);
    }

    private Long outbox(String status, int updatedMinutesAgo) {
        LocalDateTime now = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(6)", LocalDateTime.class);
        return transactions.execute(transaction -> {
            User user = new User(UUID.randomUUID() + "@example.com", "password", "입주민", null);
            user.selectRole(UserRole.RESIDENT);
            entityManager.persist(user);
            Conversation conversation = new Conversation(user, ConversationType.INQUIRY, "보일러 문의");
            entityManager.persist(conversation);
            Message message = new Message(conversation, "보일러가 안 켜져요", SenderType.RESIDENT,
                MessageType.TEXT, UUID.randomUUID().toString());
            entityManager.persist(message);
            ChatbotReplyOutbox outbox = ChatbotReplyOutbox.startNextMessage(message, user.getId(),
                UUID.randomUUID().toString(), UUID.randomUUID().toString(), null, null, now);
            entityManager.persist(outbox);
            entityManager.flush();
            jdbc.update("UPDATE Chatbot_reply_outbox SET status = ?, updated_at = ? WHERE event_id = ?",
                status, now.minusMinutes(updatedMinutesAgo), outbox.getId());
            return outbox.getId();
        });
    }

    private List<Long> remainingIds() {
        return jdbc.queryForList("SELECT event_id FROM Chatbot_reply_outbox", Long.class);
    }
}
