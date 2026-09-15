package com.homes.zipsai;

import com.homes.zipsai.auth.AuthProperties;
import com.homes.zipsai.security.RequestGuard;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;

class RequestGuardTests {
    @Test void sixthRequestIsRejectedWithRetryAfter() throws Exception {
        var guard = new RequestGuard(new AuthProperties("test-only-secret-at-least-32-bytes-long", null, null, false, null, 5), new JsonMapper());
        for (int i = 0; i < 6; i++) {
            var request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
            request.setServletPath("/api/v1/auth/login");
            var response = new MockHttpServletResponse();
            guard.doFilter(request, response, new MockFilterChain());
            assertThat(response.getStatus()).isEqualTo(i < 5 ? 200 : 429);
            if (i == 5) { assertThat(response.getHeader("Retry-After")).isNotBlank(); assertThat(response.getContentAsString()).contains("TOO_MANY_REQUESTS"); }
        }
    }
}
