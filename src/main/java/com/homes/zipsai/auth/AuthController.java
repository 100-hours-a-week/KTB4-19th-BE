package com.homes.zipsai.auth;

import com.homes.zipsai.common.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
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
    public Map<String, Object> login(@RequestBody JsonNode body, HttpServletResponse response) {
        return result(auth.login(body), response);
    }
    @PostMapping("/reissue")
    public Map<String, Object> reissue(@CookieValue(name = "refreshToken", required = false) String refresh, HttpServletResponse response) {
        return result(auth.reissue(refresh), response);
    }
    @PostMapping("/logout")
    public Map<String, Object> logout(@AuthenticationPrincipal AuthPrincipal principal, HttpServletResponse response) {
        auth.logout(principal); cookie(response, "", 0); return ApiResponse.data(null);
    }
    private Map<String, Object> result(AuthService.Tokens tokens, HttpServletResponse response) {
        cookie(response, tokens.refreshToken(), Math.max(0, Duration.between(Instant.now(), tokens.expiresAt()).toSeconds()));
        return ApiResponse.data(tokens.data());
    }
    private void cookie(HttpServletResponse response, String value, long age) {
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from("refreshToken", value).httpOnly(true)
            .secure(properties.secureCookie()).sameSite("Strict").path("/api/v1/auth").maxAge(age).build().toString());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    }
}
