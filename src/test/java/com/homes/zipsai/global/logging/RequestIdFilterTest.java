package com.homes.zipsai.global.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestIdFilterTest {
    @Test
    void responseHeaderMatchesRequestContextAndContextIsCleared() throws Exception {
        RequestIdFilter filter = new RequestIdFilter(mock(StructuredLogger.class));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/conversations");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> {
            String traceId = MDC.get("traceId");
            assertThat(UUID.fromString(traceId).toString()).isEqualTo(traceId);
            assertThat(response.getHeader("X-Request-Id")).isEqualTo(traceId);
            assertThat(MDC.get("route")).isEqualTo("/api/v1/conversations");
            response.setStatus(401);
        });
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(MDC.get("traceId")).isNull();
        assertThat(MDC.get("route")).isNull();
    }
}
