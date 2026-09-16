package com.homes.zipsai.auth.controller;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import com.homes.zipsai.auth.dto.LoginRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import com.homes.zipsai.auth.service.AuthService;
import com.homes.zipsai.global.response.ApiResponse;
import com.homes.zipsai.global.security.AuthPrincipal;
import com.homes.zipsai.global.security.AuthProperties;

import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthService auth;
    private final AuthProperties properties;
    public AuthController(AuthService auth, AuthProperties properties) { this.auth = auth; this.properties = properties; }
    // V3_P1_1: 가입 후 로그인 화면으로 이동한다.
    @PostMapping("/signup")
    public ResponseEntity<?> signup(@RequestBody JsonNode body) {
        return ResponseEntity.status(201).body(Map.of("message", "user_created", "data", Map.of("userId", auth.signup(body))));
    }
    @PostMapping("/login")
    public ApiResponse<Map<String, Object>> login(@RequestBody LoginRequest loginRequest, HttpServletResponse response) {
        return result(auth.login(loginRequest), response);
    }
    @PostMapping("/reissue")
    public ApiResponse<Map<String, Object>> reissue(@CookieValue(name = "refreshToken", required = false) String refresh, HttpServletResponse response) {
        return result(auth.reissue(refresh), response);
    }
    @PostMapping("/logout")
    public ApiResponse<Void> logout(@AuthenticationPrincipal AuthPrincipal principal, HttpServletResponse response) {
        auth.logout(principal); cookie(response, "", 0); return ApiResponse.data(null);
    }
    private ApiResponse<Map<String, Object>> result(AuthService.Tokens tokens, HttpServletResponse response) {
        cookie(response, tokens.refreshToken(), Math.max(0, Duration.between(Instant.now(), tokens.expiresAt()).toSeconds()));
        return ApiResponse.data(tokens.data());
    }
    private void cookie(HttpServletResponse response, String value, long age) {
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from("refreshToken", value).httpOnly(true)
            .secure(properties.secureCookie()).sameSite("Strict").path("/api/v1/auth").maxAge(age).build().toString());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    }
}
