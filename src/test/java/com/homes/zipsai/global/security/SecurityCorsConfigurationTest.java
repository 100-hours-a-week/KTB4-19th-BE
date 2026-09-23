package com.homes.zipsai.global.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;

class SecurityCorsConfigurationTest {

    private static final String ORIGIN = "https://zipsai.co.kr";

    @Test
    @DisplayName("초대코드 연결에 쓰는 PUT을 허용한다")
    void allowsPutUsedByRoomConnection() {
        assertThat(corsConfiguration().getAllowedMethods()).contains("PUT");
    }

    @Test
    @DisplayName("본문을 보내는 메서드를 모두 허용한다")
    void allowsEveryMethodTheApiUses() {
        assertThat(corsConfiguration().getAllowedMethods())
            .contains("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");
    }

    @Test
    @DisplayName("허용한 출처의 요청에 설정을 적용한다")
    void appliesConfigurationToAllowedOrigin() {
        MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/api/v1/residents/me/room");
        request.addHeader("Origin", ORIGIN);

        CorsConfiguration configuration = new SecurityCorsConfiguration()
            .corsConfigurationSource(properties())
            .getCorsConfiguration(request);

        assertThat(configuration).isNotNull();
        assertThat(configuration.checkOrigin(ORIGIN)).isEqualTo(ORIGIN);
    }

    private static CorsConfiguration corsConfiguration() {
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/v1/residents/me/room");
        return new SecurityCorsConfiguration()
            .corsConfigurationSource(properties())
            .getCorsConfiguration(request);
    }

    private static AuthProperties properties() {
        return new AuthProperties("test-only-secret-at-least-32-bytes-long", null, null, false,
            List.of(ORIGIN), 5);
    }
}
