package com.homes.zipsai.auth.controller;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.homes.zipsai.auth.dto.LoginRequest;
import com.homes.zipsai.auth.dto.LoginResponse;
import com.homes.zipsai.auth.dto.ReissueResponse;
import com.homes.zipsai.auth.dto.SignupRequest;
import com.homes.zipsai.auth.dto.SignupResponse;
import com.homes.zipsai.auth.service.AuthService;
import com.homes.zipsai.global.response.ApiResponse;
import com.homes.zipsai.global.security.AuthPrincipal;
import com.homes.zipsai.global.security.AuthProperties;
import com.homes.zipsai.user.domain.UserRole;
import com.homes.zipsai.user.dto.UserResponse;

import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final AuthProperties properties;

    // V3_P1_1: 가입 후 로그인 화면으로 이동한다.
    @PostMapping("/signup")
    public ResponseEntity<ApiResponse<SignupResponse>> signup(@RequestBody SignupRequest request) {
        SignupResponse response = new SignupResponse(authService.signup(request));
        return ResponseEntity.status(201).body(ApiResponse.data(response));
    }

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(
            @RequestBody LoginRequest loginRequest,
            HttpServletResponse response
    ) {
        return result(authService.login(loginRequest), response);
    }

    @PostMapping("/reissue")
    public ApiResponse<ReissueResponse> reissue(
            @CookieValue(name = "refreshToken", required = false) String refresh,
            HttpServletResponse response
    ) {
        AuthService.Tokens tokens = authService.reissue(refresh);
        long maxAge = Math.max(
                0,
                Duration.between(Instant.now(), tokens.expiresAt()).toSeconds()
        );
        cookie(response, tokens.refreshToken(), maxAge);

        Map<String, Object> data = tokens.data();
        ReissueResponse reissueResponse = new ReissueResponse(
                (String) data.get("accessToken"),
                (String) data.get("tokenType")
        );
        return ApiResponse.data(reissueResponse);
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(
            @AuthenticationPrincipal AuthPrincipal principal,
            @CookieValue(name = "refreshToken", required = false) String refresh,
            HttpServletResponse response
    ) {
        authService.logout(principal, refresh);
        cookie(response, "", 0);
        return ApiResponse.data(null);
    }

    private UserResponse toUserResponse(Object value) {
        if (!(value instanceof Map<?, ?> user)) {
            throw new IllegalStateException("user 응답 형식이 올바르지 않습니다.");
        }

        return new UserResponse(
                (Long) user.get("userId"),
                (String) user.get("email"),
                (UserRole) user.get("userRole"),
                (String) user.get("userName"),
                (String) user.get("phone")
        );
    }

    private ApiResponse<LoginResponse> result(AuthService.Tokens tokens, HttpServletResponse response) {
        long maxAge = Math.max(
                0,
                Duration.between(Instant.now(), tokens.expiresAt()).toSeconds()
        );
        cookie(response, tokens.refreshToken(), maxAge);

        Map<String, Object> data = tokens.data();
        UserResponse user = toUserResponse(data.get("user"));
        LoginResponse loginResponse = new LoginResponse(
                (String) data.get("accessToken"),
                (String) data.get("tokenType"),
                user
        );
        return ApiResponse.data(loginResponse);
    }

    private void cookie(HttpServletResponse response, String value, long age) {
        ResponseCookie cookie = ResponseCookie.from("refreshToken", value)
                .httpOnly(true)
                .secure(properties.secureCookie())
                .sameSite("Strict")
                .path("/api/v1/auth")
                .maxAge(age)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    }
}
