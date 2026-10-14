package com.homes.zipsai.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import com.homes.zipsai.common.config.SseRedisPubSubConfiguration;
import com.homes.zipsai.common.domain.ComplaintNotificationContent.Type;
import com.homes.zipsai.common.event.NotificationEvent;
import com.homes.zipsai.common.service.NotificationEventPublisher;
import com.homes.zipsai.common.service.NotificationEventSubscriber;
import com.homes.zipsai.common.service.RedisMessagePublisher;
import com.homes.zipsai.common.service.RedisMessageSubscriber;
import com.homes.zipsai.global.logging.StructuredLogger;

import tools.jackson.databind.ObjectMapper;

@DisplayName("SSE Redis Pub/Sub 연결 및 수신")
class SseRedisPubSubIntegrationTest {

    private Map<String, String> previousMdc;

    @BeforeEach
    void setUp() {
        previousMdc = MDC.getCopyOfContextMap();
        MDC.clear();
        MDC.put("traceId", "trace-id");
    }

    @AfterEach
    void restoreMdc() {
        if (previousMdc == null) {
            MDC.clear();
        } else {
            MDC.setContextMap(previousMdc);
        }
    }

    @Test
    @DisplayName("Redis가 없어도 컨텍스트와 서버 준비 이벤트 처리를 실패시키지 않는다")
    void initializesWithoutRedis() {
        RedisConnectionFactory redisConnectionFactory = mock(RedisConnectionFactory.class);
        when(redisConnectionFactory.getConnection())
            .thenThrow(new RedisConnectionFailureException("test-only unavailable Redis"));

        runner().withBean(RedisConnectionFactory.class, () -> redisConnectionFactory).run(context -> {
            assertThat(context).hasNotFailed();

            assertThatCode(() -> ready(context)).doesNotThrowAnyException();

            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                verify(redisConnectionFactory, atLeastOnce()).getConnection());

            assertThat(context.isActive()).isTrue();
        });
    }

    @Test
    @DisplayName("실제 Redis 발행을 두 독립 구독자가 수신하고 다른 채널과 잘못된 메시지는 전달하지 않는다")
    void deliversToTwoIndependentSubscribers() {
        try (GenericContainer<?> redisContainer = redisContainer()) {
            redisContainer.start();
            ApplicationContextRunner connectedContextRunner = connectedRunner(redisContainer);
            BlockingQueue<ReceivedEvent> firstEvents = new LinkedBlockingQueue<>();
            BlockingQueue<ReceivedEvent> secondEvents = new LinkedBlockingQueue<>();

            connectedContextRunner.run(firstContext -> {
                captureEvents(firstContext, firstEvents);
                ready(firstContext);
                awaitListening(firstContext);
                connectedContextRunner.run(secondContext -> {
                    captureEvents(secondContext, secondEvents);
                    ready(secondContext);
                    awaitListening(secondContext);

                    NotificationEventPublisher notificationEventPublisher =
                        firstContext.getBean(NotificationEventPublisher.class);
                    for (Type type : Type.values()) {
                        NotificationEvent notificationEvent = new NotificationEvent(type, 42L, 123L);
                        notificationEventPublisher.publish(notificationEvent);

                        assertThat(firstEvents.poll(5, TimeUnit.SECONDS))
                            .isEqualTo(new ReceivedEvent(notificationEvent, "trace-id"));

                        assertThat(secondEvents.poll(5, TimeUnit.SECONDS))
                            .isEqualTo(new ReceivedEvent(notificationEvent, "trace-id"));
                    }

                    String notificationChannel = firstContext.getEnvironment()
                        .getProperty("app.redis.pubsub.notification-channel");
                    StringRedisTemplate stringRedisTemplate = firstContext.getBean(StringRedisTemplate.class);
                    stringRedisTemplate.convertAndSend(notificationChannel + ":other", """
                        {"trace_id":"trace-id","payload":{
                          "eventType":"COMPLAINT_CREATED","userId":999,"resourceId":999}}
                        """);
                    stringRedisTemplate.convertAndSend(notificationChannel, "not-json");
                    NotificationEvent afterInvalid = new NotificationEvent(Type.COMPLAINT_CREATED, 42L, 124L);
                    notificationEventPublisher.publish(afterInvalid);

                    assertThat(firstEvents.poll(5, TimeUnit.SECONDS))
                        .isEqualTo(new ReceivedEvent(afterInvalid, "trace-id"));

                    assertThat(secondEvents.poll(5, TimeUnit.SECONDS))
                        .isEqualTo(new ReceivedEvent(afterInvalid, "trace-id"));

                    assertThat(firstEvents).isEmpty();

                    assertThat(secondEvents).isEmpty();
                });
            });
        }
    }

    @Test
    @DisplayName("첫 Redis 접속 실패 후 재시도하여 실제 Redis 구독과 수신을 시작한다")
    void retriesInitialConnectionFailure() {
        try (GenericContainer<?> redisContainer = redisContainer()) {
            redisContainer.start();
            LettuceConnectionFactory redisConnectionFactory = spy(new LettuceConnectionFactory(redisContainer.getHost(),
                redisContainer.getMappedPort(6379)));
            doThrow(new RedisConnectionFailureException("test-only initial failure"))
                .doCallRealMethod().when(redisConnectionFactory).getConnection();
            BlockingQueue<ReceivedEvent> receivedEvents = new LinkedBlockingQueue<>();

            ApplicationContextRunner connectedContextRunner = connectedRunner(redisContainer)
                .withBean(LettuceConnectionFactory.class, () -> redisConnectionFactory);
            connectedContextRunner.run(context -> {
                captureEvents(context, receivedEvents);

                assertThatCode(() -> ready(context)).doesNotThrowAnyException();

                awaitListening(context);
                NotificationEvent notificationEvent = new NotificationEvent(Type.COMPLAINT_STATUS_CHANGED, 42L, 123L);
                context.getBean(NotificationEventPublisher.class).publish(notificationEvent);

                assertThat(receivedEvents.poll(5, TimeUnit.SECONDS))
                    .isEqualTo(new ReceivedEvent(notificationEvent, "trace-id"));
            });
        }
    }

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DataRedisAutoConfiguration.class))
            .withUserConfiguration(SseRedisPubSubConfiguration.class, NotificationEventPublisher.class,
                NotificationEventSubscriber.class, RedisMessagePublisher.class, RedisMessageSubscriber.class,
                StructuredLogger.class)
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withPropertyValues("app.redis.pubsub.notification-channel=test:notification-events:" + UUID.randomUUID(),
                "spring.data.redis.connect-timeout=200ms", "spring.data.redis.timeout=1s",
                "spring.data.redis.lettuce.shutdown-timeout=200ms");
    }

    private ApplicationContextRunner connectedRunner(GenericContainer<?> redisContainer) {
        return runner().withPropertyValues("spring.data.redis.host=" + redisContainer.getHost(),
            "spring.data.redis.port=" + redisContainer.getMappedPort(6379));
    }

    private GenericContainer<?> redisContainer() {
        return new GenericContainer<>(DockerImageName.parse("redis:8.2.10-alpine")).withExposedPorts(6379);
    }

    private void captureEvents(AssertableApplicationContext context, BlockingQueue<ReceivedEvent> receivedEvents) {
        assertThat(context).hasNotFailed();

        context.getSourceApplicationContext().addApplicationListener(applicationEvent -> {
            if (applicationEvent instanceof PayloadApplicationEvent<?> payloadApplicationEvent
                && payloadApplicationEvent.getPayload() instanceof NotificationEvent notificationEvent) {
                receivedEvents.add(new ReceivedEvent(notificationEvent, MDC.get("traceId")));
            }
        });
    }

    private void ready(AssertableApplicationContext context) {
        assertThat(context.getBean(RedisMessageListenerContainer.class).isAutoStartup()).isFalse();

        context.publishEvent(new ApplicationReadyEvent(new SpringApplication(), new String[0],
            context.getSourceApplicationContext(), Duration.ZERO));
    }

    private record ReceivedEvent(NotificationEvent payload, String traceId) {
    }

    private void awaitListening(AssertableApplicationContext context) {
        RedisMessageListenerContainer redisMessageListenerContainer =
            context.getBean(RedisMessageListenerContainer.class);
        await().atMost(Duration.ofSeconds(10)).until(redisMessageListenerContainer::isListening);
    }
}
