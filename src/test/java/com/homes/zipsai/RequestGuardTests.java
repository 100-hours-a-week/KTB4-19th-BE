package com.homes.zipsai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.homes.zipsai.global.security.AuthProperties;
import com.homes.zipsai.global.security.RequestGuard;

import tools.jackson.databind.json.JsonMapper;

class RequestGuardTests {
    @Test
    void sixthRequestIsRejectedWithRetryAfter() throws Exception {
        var guard = new RequestGuard(
                new AuthProperties("test-only-secret-at-least-32-bytes-long", null, null, false, null, 5),
                new JsonMapper()
        );
        for (int index = 0; index < 6; index++) {
            var request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
            request.setServletPath("/api/v1/auth/login");
            var response = new MockHttpServletResponse();
            guard.doFilter(request, response, new MockFilterChain());
            assertThat(response.getStatus()).isEqualTo(index < 5 ? 200 : 429);
            if (index == 5) {
                assertThat(response.getHeader("Retry-After")).isNotBlank();
                assertThat(response.getContentAsString()).contains("TOO_MANY_REQUESTS");
                assertThat(response.getContentAsString()).contains("\"retryAfterSeconds\":30");
            }
        }
    }
}
