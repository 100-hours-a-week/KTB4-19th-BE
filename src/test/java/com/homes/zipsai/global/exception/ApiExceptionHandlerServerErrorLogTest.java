package com.homes.zipsai.global.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.SQLTransientConnectionException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ApiExceptionHandlerServerErrorLogTest {
    private MockMvc mockMvc;
    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new FailingController())
            .setControllerAdvice(new ApiExceptionHandler())
            .build();
        logger = (Logger) LoggerFactory.getLogger("structured-events");
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        appender.stop();
        MDC.clear();
    }

    @Test
    @DisplayName("처리하지 못한 예외로 500이 나면 원인 예외를 담은 request_error 로그를 남긴다")
    void logsRootCauseForUnexpectedException() throws Exception {
        MDC.put("traceId", "trace-1");

        mockMvc.perform(post("/rooms/3/invitation-codes")).andExpect(status().isInternalServerError());

        assertThat(appender.list).hasSize(1);
        JsonNode event = JsonMapper.builder().build().readTree(appender.list.getFirst().getFormattedMessage());
        assertThat(event.path("event").asText()).isEqualTo("request_error");
        assertThat(event.path("level").asText()).isEqualTo("ERROR");
        assertThat(event.path("trace_id").asText()).isEqualTo("trace-1");
        assertThat(event.path("route").asText()).isEqualTo("/rooms/3/invitation-codes");
        assertThat(event.path("method").asText()).isEqualTo("POST");
        assertThat(event.path("status_code").asInt()).isEqualTo(500);
        assertThat(event.path("error_code").asText()).isEqualTo("INTERNAL_SERVER_ERROR");
        assertThat(event.path("exception_type").asText()).isEqualTo(CannotCreateTransactionException.class.getName());
        assertThat(event.path("root_cause_type").asText()).isEqualTo(SQLTransientConnectionException.class.getName());
        assertThat(event.path("root_cause_message").asText()).contains("request timed out after 30000ms");
    }

    @Test
    @DisplayName("4xx 예외는 request_error 로그를 남기지 않는다")
    void doesNotLogClientError() throws Exception {
        mockMvc.perform(post("/forbidden")).andExpect(status().isForbidden());

        assertThat(appender.list).isEmpty();
    }

    @RestController
    static class FailingController {
        @PostMapping("/rooms/3/invitation-codes")
        void connectionTimeout() {
            throw new CannotCreateTransactionException("Could not open JPA EntityManager for transaction",
                new SQLTransientConnectionException(
                    "HikariPool-1 - Connection is not available, request timed out after 30000ms"));
        }

        @PostMapping("/forbidden")
        void forbidden() {
            throw new ForbiddenException();
        }
    }
}
