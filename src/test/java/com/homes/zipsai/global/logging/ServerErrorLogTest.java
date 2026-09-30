package com.homes.zipsai.global.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLTransientConnectionException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.CannotCreateTransactionException;

class ServerErrorLogTest {
    private final MockHttpServletRequest request =
        new MockHttpServletRequest("POST", "/api/v1/managers/me/rooms/3/invitation-codes");

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("요청 정보와 예외 타입, 가장 안쪽 원인 예외를 담는다")
    void containsRequestAndRootCause() {
        MDC.put("traceId", "trace-1");
        MDC.put(StructuredLogger.ERROR_CODE, "INTERNAL_SERVER_ERROR");
        CannotCreateTransactionException exception = new CannotCreateTransactionException("Could not open",
            new SQLTransientConnectionException("Connection is not available, request timed out after 30000ms"));

        ServerErrorLog event = ServerErrorLog.of(request, exception);

        assertThat(event.event()).isEqualTo("request_error");
        assertThat(event.traceId()).isEqualTo("trace-1");
        assertThat(event.route()).isEqualTo("/api/v1/managers/me/rooms/3/invitation-codes");
        assertThat(event.method()).isEqualTo("POST");
        assertThat(event.statusCode()).isEqualTo(500);
        assertThat(event.errorCode()).isEqualTo("INTERNAL_SERVER_ERROR");
        assertThat(event.exceptionType()).isEqualTo(CannotCreateTransactionException.class.getName());
        assertThat(event.rootCauseType()).isEqualTo(SQLTransientConnectionException.class.getName());
        assertThat(event.rootCauseMessage()).contains("request timed out after 30000ms");
    }

    @Test
    @DisplayName("원인 예외가 없으면 예외 자신을 원인으로 담는다")
    void usesExceptionItselfWithoutCause() {
        ServerErrorLog event = ServerErrorLog.of(request, new IllegalStateException("failure"));

        assertThat(event.rootCauseType()).isEqualTo(IllegalStateException.class.getName());
        assertThat(event.rootCauseMessage()).isEqualTo("failure");
    }

    @Test
    @DisplayName("에러 코드가 기록되지 않았으면 INTERNAL_SERVER_ERROR를 쓴다")
    void usesDefaultErrorCode() {
        ServerErrorLog event = ServerErrorLog.of(request, new IllegalStateException("failure"));

        assertThat(event.errorCode()).isEqualTo("INTERNAL_SERVER_ERROR");
    }

    @Test
    @DisplayName("메시지의 따옴표 안 값과 이메일은 가린다")
    void masksQuotedValuesAndEmails() {
        ServerErrorLog event = ServerErrorLog.of(request, new IllegalStateException(
            "Duplicate entry 'AB23CD' for key 'uk_code', user resident@zipsai.co.kr"));

        assertThat(event.errorMessage()).isEqualTo("Duplicate entry '?' for key '?', user ***");
    }

    @Test
    @DisplayName("여러 줄 메시지는 한 줄로 합치고 2000자에서 자른다")
    void flattensAndTruncatesMessage() {
        ServerErrorLog event = ServerErrorLog.of(request,
            new IllegalStateException("첫 줄\n둘째 줄 " + "x".repeat(2100)));

        assertThat(event.errorMessage()).startsWith("첫 줄 둘째 줄 ").hasSize(2000);
    }

    @Test
    @DisplayName("메시지가 없는 예외는 메시지를 null로 둔다")
    void keepsNullMessage() {
        ServerErrorLog event = ServerErrorLog.of(request, new NullPointerException());

        assertThat(event.errorMessage()).isNull();
    }
}
