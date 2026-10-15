package com.homes.zipsai.common.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.connection.DefaultMessage;

import com.homes.zipsai.common.config.RedisPubSubProperties;
import com.homes.zipsai.common.domain.ComplaintNotificationContent.Type;
import com.homes.zipsai.common.event.NotificationEvent;
import com.homes.zipsai.global.logging.StructuredLogger;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@DisplayName("알림 이벤트 Redis 수신")
class NotificationEventSubscriberTest {

    private static final String TRACE_ID = "trace-id";

    private ApplicationEventPublisher applicationEventPublisher;
    private NotificationEventSubscriber notificationEventSubscriber;
    private Logger logger;
    private ListAppender<ILoggingEvent> logAppender;
    private ObjectMapper objectMapper;
    private Map<String, String> previousMdc;

    @BeforeEach
    void setUp() {
        previousMdc = MDC.getCopyOfContextMap();
        MDC.clear();
        applicationEventPublisher = mock(ApplicationEventPublisher.class);
        objectMapper = new ObjectMapper();
        notificationEventSubscriber = new NotificationEventSubscriber(
            new RedisMessageSubscriber(objectMapper, new StructuredLogger(objectMapper)), applicationEventPublisher,
            new RedisPubSubProperties("test:notification-events"));
        logger = (Logger) LoggerFactory.getLogger("structured-events");
        logAppender = new ListAppender<>();
        logAppender.start();
        logger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logAppender);
        logAppender.stop();
        if (previousMdc == null) {
            MDC.clear();
        } else {
            MDC.setContextMap(previousMdc);
        }
    }

    @ParameterizedTest
    @EnumSource(Type.class)
    @DisplayName("두 알림 타입의 payload를 수신 추적값이 설정된 상태에서 내부 이벤트로 전달한다")
    void dispatchesValidNotificationEvent(Type eventType) {
        List<String> observedTraceIds = new ArrayList<>();
        doAnswer(invocation -> {
            observedTraceIds.add(MDC.get("traceId"));
            return null;
        }).when(applicationEventPublisher).publishEvent(any(NotificationEvent.class));

        notificationEventSubscriber.onMessage(message("""
            {"trace_id":"%s","payload":{"eventType":"%s","userId":42,"resourceId":123}}
            """.formatted(TRACE_ID, eventType.name())), null);

        verify(applicationEventPublisher).publishEvent(new NotificationEvent(eventType, 42L, 123L));

        assertThat(observedTraceIds).containsExactly(TRACE_ID);

        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{}",
        "{\"eventType\":\"UNKNOWN\",\"userId\":42,\"resourceId\":123}",
        "{\"eventType\":0,\"userId\":42,\"resourceId\":123}",
        "{\"eventType\":\"COMPLAINT_CREATED\",\"userId\":0,\"resourceId\":123}",
        "{\"eventType\":\"COMPLAINT_CREATED\",\"userId\":42,\"resourceId\":-1}",
        "{\"eventType\":\"COMPLAINT_CREATED\",\"userId\":42.5,\"resourceId\":123}"
    })
    @DisplayName("유효하지 않은 알림 payload는 폐기하고 내부 이벤트를 발생시키지 않는다")
    void discardsInvalidMessage(String payload) {
        assertThatCode(() -> notificationEventSubscriber.onMessage(message(
            "{\"trace_id\":\"trace-id\",\"payload\":" + payload + "}"), null)).doesNotThrowAnyException();

        verifyNoInteractions(applicationEventPublisher);

        assertThat(logAppender.list).hasSize(1);

        JsonNode log = objectMapper.readTree(logAppender.list.getFirst().getFormattedMessage());

        assertThat(log.path("trace_id").asText()).isEqualTo(TRACE_ID);

        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    @DisplayName("검증 실패 필드와 원인을 기록하며 잘못된 원문은 로그에 포함하지 않는다")
    void logsValidationFieldWithoutPayload() {
        Map<String, String> workerContext = Map.of("traceId", "previous-worker", "route", "/previous");
        MDC.setContextMap(workerContext);
        notificationEventSubscriber.onMessage(message("""
            {"trace_id":"trace-id","payload":{
              "eventType":"COMPLAINT_CREATED","userId":0,"resourceId":123,"privateText":"do-not-log"}}
            """), null);

        assertThat(logAppender.list).extracting(ILoggingEvent::getFormattedMessage)
            .anySatisfy(logMessage -> assertThat(logMessage)
                .contains("\"field\":\"userId\"", "\"reason\":\"INVALID_ID\"")
                .doesNotContain("do-not-log"));

        JsonNode log = objectMapper.readTree(logAppender.list.getFirst().getFormattedMessage());

        assertThat(log.path("trace_id").asText()).isEqualTo(TRACE_ID);

        assertThat(log.path("route").isNull()).isTrue();

        assertThat(MDC.getCopyOfContextMap()).isEqualTo(workerContext);
    }

    private DefaultMessage message(String payload) {
        return new DefaultMessage("test:notification-events".getBytes(StandardCharsets.UTF_8),
            payload.getBytes(StandardCharsets.UTF_8));
    }
}
