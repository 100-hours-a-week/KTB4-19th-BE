package com.homes.zipsai.global.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

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
        MDC.remove("route");
        MDC.remove("method");
        logger.detachAppender(appender);
        appender.stop();
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
