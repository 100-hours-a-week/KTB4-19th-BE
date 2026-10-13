package com.homes.zipsai.global.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import io.micrometer.common.KeyValue;

class ErrorCodeObservationConventionTest {

    ErrorCodeObservationConvention convention = new ErrorCodeObservationConvention();

    @AfterEach
    void tearDown() {
        MDC.remove(StructuredLogger.ERROR_CODE);
    }

    @Test
    @DisplayName("요청 처리 중 남긴 에러 코드를 API 지표 라벨로 붙인다")
    void addsErrorCodeFromRequest() {
        MDC.put(StructuredLogger.ERROR_CODE, "COMPLAINT_NOT_FOUND");

        Iterable<KeyValue> keyValues = convention.getLowCardinalityKeyValues(context());

        assertThat(keyValues).contains(KeyValue.of("error_code", "COMPLAINT_NOT_FOUND"));
    }

    @Test
    @DisplayName("에러 코드가 없으면 none으로 붙인다")
    void addsNoneWithoutErrorCode() {
        Iterable<KeyValue> keyValues = convention.getLowCardinalityKeyValues(context());

        assertThat(keyValues).contains(KeyValue.of("error_code", "none"));
    }

    private ServerRequestObservationContext context() {
        return new ServerRequestObservationContext(new MockHttpServletRequest("GET", "/api/v1/terms"),
            new MockHttpServletResponse());
    }
}
