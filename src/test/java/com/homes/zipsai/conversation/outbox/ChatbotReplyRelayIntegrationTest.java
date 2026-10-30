package com.homes.zipsai.conversation.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
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
@DirtiesContext
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:tc:mysql:8.4.11:///zipsai_chatbot_reply_relay?TC_DAEMON=true",
    "spring.datasource.username=test",
    "spring.datasource.password=test",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.jpa.hibernate.naming.physical-strategy=org.hibernate.boot.model.naming.PhysicalNamingStrategyStandardImpl",
    "spring.jpa.defer-datasource-initialization=false",
    "spring.flyway.enabled=true",
    "spring.rabbitmq.publisher-confirm-type=correlated",
    "spring.rabbitmq.publisher-returns=true",
    "spring.rabbitmq.template.mandatory=true",
    "app.conversation.reply-relay.republish-delay=10s"
})
class ChatbotReplyRelayIntegrationTest {

    private static final String EXCHANGE = "chatbot.reply";
    private static final String QUEUE = "chatbot.reply.request";
    private static final GenericContainer<?> REDIS =
        new GenericContainer<>(DockerImageName.parse("redis:8.2.10-alpine")).withExposedPorts(6379);
    private static final GenericContainer<?> RABBITMQ =
        new GenericContainer<>(DockerImageName.parse("rabbitmq:4.3.6-management")).withExposedPorts(5672);

    @Autowired
    ChatbotReplyRelayService relayService;

    @Autowired
    EntityManager entityManager;

    @Autowired
    TransactionTemplate transactions;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    DataSource dataSource;

    @Autowired
    AmqpAdmin amqpAdmin;

    @Autowired
    RabbitTemplate rabbitTemplate;

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        REDIS.start();
        RABBITMQ.start();
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.rabbitmq.host", RABBITMQ::getHost);
        registry.add("spring.rabbitmq.port", () -> RABBITMQ.getMappedPort(5672));
    }

    @AfterAll
    static void stopContainers() {
        RABBITMQ.stop();
        REDIS.stop();
    }

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM Chatbot_reply_outbox");
        amqpAdmin.declareExchange(new DirectExchange(EXCHANGE, true, false));
        amqpAdmin.declareQueue(new Queue(QUEUE, true, false, false, Map.of("x-queue-type", "quorum")));
        amqpAdmin.declareBinding(binding());
        amqpAdmin.purgeQueue(QUEUE, false);
    }

    @Test
    @DisplayName("발행 기록이 없고 10초가 지난 대기 요청을 발행하고 브로커 확인 후 발행 시각을 남긴다")
    void publishesPendingRequestAndMarksPublished() {
        Long eventId = outbox("PENDING", 1, 11, false);

        int marked = relayService.relayBatch();

        assertThat(marked).isEqualTo(1);

        assertThat(publishedAt(eventId)).isNotNull();

        org.springframework.amqp.core.Message message = rabbitTemplate.receive(QUEUE, 5_000);
        assertThat(message).isNotNull();

        assertThat(new String(message.getBody(), StandardCharsets.UTF_8))
            .isEqualTo("{\"eventId\":" + eventId + ",\"attempt\":1}");

        assertThat(relayService.relayBatch()).isZero();
    }

    @Test
    @DisplayName("최근에 바뀐 요청, 처리 중인 요청, 이미 발행한 요청은 발행하지 않는다")
    void skipsIneligibleRequests() {
        Long recent = outbox("PENDING", 0, 3, false);
        Long processing = outbox("PROCESSING", 0, 11, false);
        Long published = outbox("PENDING", 0, 11, true);
        LocalDateTime publishedAt = publishedAt(published);

        int marked = relayService.relayBatch();

        assertThat(marked).isZero();

        assertThat(publishedAt(recent)).isNull();

        assertThat(publishedAt(processing)).isNull();

        assertThat(publishedAt(published)).isEqualTo(publishedAt);

        assertThat(rabbitTemplate.receive(QUEUE, 500)).isNull();
    }

    @Test
    @DisplayName("큐로 라우팅되지 않은 요청은 발행 시각을 남기지 않아 다음 주기에 다시 발행한다")
    void doesNotMarkUnroutedRequest() {
        Long eventId = outbox("PENDING", 0, 11, false);
        amqpAdmin.removeBinding(binding());

        int marked = relayService.relayBatch();

        assertThat(marked).isZero();

        assertThat(publishedAt(eventId)).isNull();

        amqpAdmin.declareBinding(binding());

        assertThat(relayService.relayBatch()).isEqualTo(1);

        assertThat(publishedAt(eventId)).isNotNull();
    }

    @Test
    @DisplayName("다른 릴레이가 잠근 요청은 건너뛰고 잠기지 않은 요청만 발행한다")
    void skipsRequestLockedByAnotherRelay() throws Exception {
        Long locked = outbox("PENDING", 0, 11, false);
        Long free = outbox("PENDING", 0, 11, false);

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT event_id FROM Chatbot_reply_outbox WHERE event_id = ? FOR UPDATE")) {
                statement.setLong(1, locked);
                statement.executeQuery();

                int marked = relayService.relayBatch();

                assertThat(marked).isEqualTo(1);
            } finally {
                connection.rollback();
            }
        }

        assertThat(publishedAt(locked)).isNull();

        assertThat(publishedAt(free)).isNotNull();
    }

    private Long outbox(String status, int attempt, int updatedSecondsAgo, boolean published) {
        LocalDateTime now = databaseTime();
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
                UUID.randomUUID().toString(), UUID.randomUUID().toString(), null, now);
            entityManager.persist(outbox);
            entityManager.flush();
            jdbc.update("""
                UPDATE Chatbot_reply_outbox SET status = ?, attempt_count = ?, published_at = ?, updated_at = ?
                WHERE event_id = ?
                """, status, attempt, published ? now.minusSeconds(20) : null,
                now.minusSeconds(updatedSecondsAgo), outbox.getId());
            return outbox.getId();
        });
    }

    private LocalDateTime publishedAt(Long eventId) {
        return jdbc.queryForObject("SELECT published_at FROM Chatbot_reply_outbox WHERE event_id = ?",
            LocalDateTime.class, eventId);
    }

    private LocalDateTime databaseTime() {
        return jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(6)", LocalDateTime.class);
    }

    private static Binding binding() {
        return BindingBuilder.bind(new Queue(QUEUE)).to(new DirectExchange(EXCHANGE)).with(QUEUE);
    }
}
