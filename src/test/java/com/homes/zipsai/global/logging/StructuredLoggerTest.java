package com.homes.zipsai.global.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.data.redis.RedisConnectionFailureException;

import com.homes.zipsai.global.exception.InvalidPubSubFieldException;
import com.homes.zipsai.global.exception.InvalidPubSubFieldException.Reason;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class StructuredLoggerTest {
    private final Logger logger = (Logger) org.slf4j.LoggerFactory.getLogger("structured-events");
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @AfterEach
    void clearRequestContext() {
        MDC.remove("traceId");
        MDC.remove("route");
        MDC.remove("method");
        logger.detachAppender(appender);
        appender.stop();
    }

    @Test
    @DisplayName("Pub/Sub 발행 실패는 현재 요청 MDC와 달라도 전달한 추적값으로 기록한다")
    void logsPubSubFailureWithMessageTraceId() {
        appender.start();
        logger.addAppender(appender);
        MDC.put("traceId", "other-trace-id");
        MDC.put("route", "/api/v1/test");
        ObjectMapper objectMapper = new ObjectMapper();

        new StructuredLogger(objectMapper).pubSubFailed("publish", "notification-events", "trace-id",
            new RedisConnectionFailureException("do-not-log"));

        assertThat(appender.list).hasSize(1);

        assertThat(appender.list.getFirst().getLevel()).isEqualTo(Level.WARN);

        String logMessage = appender.list.getFirst().getFormattedMessage();
        JsonNode event = objectMapper.readTree(logMessage);

        assertThat(event.path("event").asText()).isEqualTo("redis_pubsub_error");

        assertThat(event.path("level").asText()).isEqualTo("WARN");

        assertThat(event.path("outcome").asText()).isEqualTo("fail");

        assertThat(event.path("operation").asText()).isEqualTo("publish");

        assertThat(event.path("channel").asText()).isEqualTo("notification-events");

        assertThat(event.path("trace_id").asText()).isEqualTo("trace-id");

        assertThat(event.path("route").asText()).isEqualTo("/api/v1/test");

        assertThat(event.path("exception_type").asText()).isEqualTo(RedisConnectionFailureException.class.getName());

        assertThat(logMessage).doesNotContain("do-not-log", "duration_ms");
    }

    @Test
    @DisplayName("요청 밖의 Pub/Sub 검증 실패는 필드와 원인만 기록하고 원문을 노출하지 않는다")
    void logsPubSubValidationWithoutRequestOrPayload() {
        appender.start();
        logger.addAppender(appender);
        ObjectMapper objectMapper = new ObjectMapper();

        new StructuredLogger(objectMapper).pubSubFailed("receive", "notification-events", null,
            new IllegalArgumentException("do-not-log", new InvalidPubSubFieldException("userId", Reason.INVALID_ID)));

        assertThat(appender.list).hasSize(1);

        String logMessage = appender.list.getFirst().getFormattedMessage();
        JsonNode event = objectMapper.readTree(logMessage);

        assertThat(event.path("trace_id").isNull()).isTrue();

        assertThat(event.path("route").isNull()).isTrue();

        assertThat(event.path("field").asText()).isEqualTo("userId");

        assertThat(event.path("reason").asText()).isEqualTo("INVALID_ID");

        assertThat(logMessage).doesNotContain("do-not-log");
    }

    @Test
    @DisplayName("Pub/Sub 로그 생성이 실패해도 기존 오류 처리를 방해하지 않는다")
    void isolatesPubSubLoggingFailure() {
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        when(objectMapper.writeValueAsString(any())).thenThrow(new IllegalStateException("test-only logging failure"));
        StructuredLogger structuredLogger = new StructuredLogger(objectMapper);

        assertThatCode(() -> structuredLogger.pubSubFailed("subscribe", "notification-events", null,
            new RedisConnectionFailureException("test-only connection failure"))).doesNotThrowAnyException();

        verify(objectMapper).writeValueAsString(any());
    }

    @Test
    @DisplayName("요청 stage 로그에 원래 HTTP method를 포함한다")
    void includesHttpMethodInStageEvent() throws Exception {
        appender.start();
        logger.addAppender(appender);
        MDC.put("route", "/api/v1/test");
        MDC.put("method", "POST");

        new StructuredLogger(new ObjectMapper()).stageDone("trace-123", "/api/v1/test", "mysql_connection_acquire",
            0, "ok", null);

        JsonNode event = new ObjectMapper().readTree(appender.list.getFirst().getFormattedMessage());

        assertThat(event.path("trace_id").asText()).isEqualTo("trace-123");

        assertThat(event.path("route").asText()).isEqualTo("/api/v1/test");

        assertThat(event.path("method").asText()).isEqualTo("POST");

        assertThat(event.path("stage").asText()).isEqualTo("mysql_connection_acquire");

        assertThat(event.path("duration_ms").asLong()).isZero();
    }
}
