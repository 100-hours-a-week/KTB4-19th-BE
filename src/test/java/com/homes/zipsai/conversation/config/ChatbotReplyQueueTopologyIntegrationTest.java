package com.homes.zipsai.conversation.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.support.GenericApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import com.homes.zipsai.conversation.outbox.ChatbotReplyRelayProperties;
import com.rabbitmq.client.GetResponse;

class ChatbotReplyQueueTopologyIntegrationTest {

    private static final String EXCHANGE = "chatbot.reply";
    private static final String ROUTING_KEY = "chatbot.reply.request";
    private static final String REQUEST_QUEUE = "test.reply.request";
    private static final String DEAD_LETTER_QUEUE = "test.reply.request.dlq";
    private static final Duration DELAYED_RETRY_MIN = Duration.ofSeconds(1);
    private static final Duration DELAYED_RETRY_MAX = Duration.ofSeconds(2);
    private static final GenericContainer<?> RABBITMQ =
        new GenericContainer<>(DockerImageName.parse("rabbitmq:4.3.6-management")).withExposedPorts(5672);

    private static CachingConnectionFactory connectionFactory;
    private static RabbitAdmin rabbitAdmin;
    private static RabbitTemplate rabbitTemplate;

    @BeforeAll
    static void declareTopology() {
        RABBITMQ.start();
        connectionFactory = new CachingConnectionFactory(RABBITMQ.getHost(), RABBITMQ.getMappedPort(5672));
        rabbitTemplate = new RabbitTemplate(connectionFactory);
        rabbitAdmin = new RabbitAdmin(connectionFactory);
        GenericApplicationContext context = new GenericApplicationContext();
        context.registerBean("chatbotReplyQueues", Declarables.class,
            () -> new ChatbotReplyQueueConfiguration().chatbotReplyQueues(new ChatbotReplyRelayProperties(EXCHANGE,
                ROUTING_KEY, Duration.ofSeconds(10), Duration.ofSeconds(5), 50),
                new ChatbotReplyQueueProperties(REQUEST_QUEUE, DEAD_LETTER_QUEUE, DELAYED_RETRY_MIN,
                    DELAYED_RETRY_MAX)));
        context.refresh();
        rabbitAdmin.setApplicationContext(context);
        rabbitAdmin.afterPropertiesSet();
        rabbitAdmin.initialize();
    }

    @AfterAll
    static void closeConnection() {
        connectionFactory.destroy();
        RABBITMQ.stop();
    }

    @BeforeEach
    void purgeQueues() {
        rabbitAdmin.purgeQueue(REQUEST_QUEUE, false);
        rabbitAdmin.purgeQueue(DEAD_LETTER_QUEUE, false);
    }

    @Test
    @DisplayName("챗봇 요청 교환기로 발행한 메시지는 설정한 이름의 요청 큐에 도착한다")
    void routesPublishedRequestToRequestQueue() {
        rabbitTemplate.send(EXCHANGE, ROUTING_KEY, message("900"));

        assertThat(takeMessageId(REQUEST_QUEUE)).isEqualTo("900");
    }

    @Test
    @DisplayName("다시 넣지 않고 거절한 요청은 설정한 이름의 DLQ로 간다")
    void deadLettersRequestRejectedWithoutRequeue() {
        rabbitTemplate.send(EXCHANGE, ROUTING_KEY, message("901"));

        rejectNext(REQUEST_QUEUE, false);

        assertThat(takeMessageId(DEAD_LETTER_QUEUE)).isEqualTo("901");
    }

    @Test
    @DisplayName("다시 넣기 거절이 재전달 한도 2번을 넘으면 요청은 DLQ로 간다")
    void deadLettersRequestOverDeliveryLimit() {
        rabbitTemplate.send(EXCHANGE, ROUTING_KEY, message("902"));

        for (int delivery = 0; delivery <= ChatbotReplyQueueConfiguration.DELIVERY_LIMIT; delivery++) {
            rejectNext(REQUEST_QUEUE, true);
        }

        assertThat(takeMessageId(DEAD_LETTER_QUEUE)).isEqualTo("902");
    }

    @Test
    @DisplayName("다시 넣기로 거절한 요청은 최소 대기 시간이 지난 뒤에 다시 배달된다")
    void redeliversRequeuedRequestAfterDelayedRetryMin() {
        rabbitTemplate.send(EXCHANGE, ROUTING_KEY, message("903"));
        rejectNext(REQUEST_QUEUE, true);
        long rejectedAt = System.nanoTime();

        String messageId = takeMessageId(REQUEST_QUEUE);

        assertThat(messageId).isEqualTo("903");
        assertThat(Duration.ofNanos(System.nanoTime() - rejectedAt)).isGreaterThanOrEqualTo(DELAYED_RETRY_MIN);
    }

    private static Message message(String eventId) {
        return MessageBuilder.withBody(("{\"eventId\":" + eventId + "}").getBytes(StandardCharsets.UTF_8))
            .setMessageId(eventId)
            .build();
    }

    private static String takeMessageId(String queue) {
        GetResponse delivery = await().atMost(Duration.ofSeconds(5))
            .until(() -> rabbitTemplate.execute(channel -> channel.basicGet(queue, true)),
                response -> response != null);
        return delivery.getProps().getMessageId();
    }

    private static void rejectNext(String queue, boolean requeue) {
        await().atMost(Duration.ofSeconds(5)).until(() -> rabbitTemplate.execute(channel -> {
            GetResponse delivery = channel.basicGet(queue, false);
            if (delivery == null) {
                return false;
            }
            channel.basicReject(delivery.getEnvelope().getDeliveryTag(), requeue);
            return true;
        }));
    }
}
