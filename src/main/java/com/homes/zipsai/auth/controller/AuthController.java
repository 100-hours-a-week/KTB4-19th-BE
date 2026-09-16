package com.homes.zipsai.auth.controller;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import com.homes.zipsai.auth.dto.LoginRequest;
import com.homes.zipsai.auth.dto.LoginResponse;
import com.homes.zipsai.auth.dto.ReissueResponse;
import com.homes.zipsai.auth.dto.SignupResponse;
import jakarta.servlet.http.HttpServletResponse;

import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import com.homes.zipsai.auth.service.AuthService;
import com.homes.zipsai.global.response.ApiResponse;
import com.homes.zipsai.global.security.AuthPrincipal;
import com.homes.zipsai.global.security.AuthProperties;

import tools.jackson.databind.JsonNode;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthService authService;
    private final AuthProperties properties;
    // V3_P1_1: 가입 후 로그인 화면으로 이동한다.
    @PostMapping("/signup")
    public ResponseEntity<ApiResponse<SignupResponse>> signup(@RequestBody JsonNode body) {
        return ResponseEntity.status(201).body(ApiResponse.data(new SignupResponse(authService.signup(body))));
    }
    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@RequestBody LoginRequest loginRequest, HttpServletResponse response) {
        return result(authService.login(loginRequest), response);
    }
    @PostMapping("/reissue")
    public ApiResponse<ReissueResponse> reissue(@CookieValue(name = "refreshToken", required = false) String refresh, HttpServletResponse response) {
        AuthService.Tokens tokens = authService.reissue(refresh);
        cookie(response, tokens.refreshToken(), Math.max(0, Duration.between(Instant.now(), tokens.expiresAt()).toSeconds()));
        Map<String, Object> data = tokens.data();
        return ApiResponse.data(new ReissueResponse((String) data.get("accessToken"), (String) data.get("tokenType")));
    }
    @PostMapping("/logout")
    public ApiResponse<Void> logout(@AuthenticationPrincipal AuthPrincipal principal, HttpServletResponse response) {
        authService.logout(principal);
        cookie(response, "", 0);
        return ApiResponse.data(null);
    }
    @SuppressWarnings("unchecked")
    private ApiResponse<LoginResponse> result(AuthService.Tokens tokens, HttpServletResponse response) {
        cookie(response, tokens.refreshToken(), Math.max(0, Duration.between(Instant.now(), tokens.expiresAt()).toSeconds()));
        Map<String, Object> data = tokens.data();
        return ApiResponse.data(new LoginResponse((String) data.get("accessToken"), (String) data.get("tokenType"), (Map<String, Object>) data.get("user")));
    }
    private void cookie(HttpServletResponse response, String value, long age) {
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from("refreshToken", value).httpOnly(true)
            .secure(properties.secureCookie()).sameSite("Strict").path("/api/v1/auth").maxAge(age).build().toString());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    }
}
