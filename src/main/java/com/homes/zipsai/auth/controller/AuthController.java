package com.homes.zipsai.auth.controller;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.homes.zipsai.auth.dto.request.LoginRequest;
import com.homes.zipsai.auth.dto.request.SignupRequest;
import com.homes.zipsai.auth.dto.response.LoginResponse;
import com.homes.zipsai.auth.dto.response.ReissueResponse;
import com.homes.zipsai.auth.dto.response.SignupResponse;
import com.homes.zipsai.auth.service.AuthService;
import com.homes.zipsai.auth.validator.RefreshTokenValidator;
import com.homes.zipsai.global.response.ApiResponse;
import com.homes.zipsai.global.security.AuthPrincipal;
import com.homes.zipsai.global.security.AuthProperties;
import com.homes.zipsai.user.domain.UserRole;
import com.homes.zipsai.user.dto.response.UserResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/auth")
@Tag(name = "인증", description = "회원가입, 로그인, 토큰 재발급과 로그아웃을 처리한다.")
public class AuthController {
    private final AuthService authService;
    private final AuthProperties authProperties;

    @PostMapping("/signup")
    @Operation(summary = "회원가입")
    public ResponseEntity<ApiResponse<SignupResponse>> signup(
            @Valid @RequestBody SignupRequest request
    ) {
        SignupResponse response = new SignupResponse(authService.signup(
                request.email(), request.password(), request.passwordConfirm(),
                request.userName(), request.phone(), request.agreements()));
        return ResponseEntity.status(201).body(ApiResponse.data(response));
    }

    @PostMapping("/login")
    @Operation(summary = "로그인", description = "access token을 응답하고 refresh token을 쿠키로 발급한다.")
    public ApiResponse<LoginResponse> login(
            @Valid @RequestBody LoginRequest loginRequest,
            HttpServletResponse response
    ) {
        return result(authService.login(loginRequest.email(), loginRequest.password()), response);
    }

    @PostMapping("/reissue")
    @Operation(summary = "access token 재발급")
    public ApiResponse<ReissueResponse> reissue(
            @CookieValue(name = "refreshToken", required = false) String refresh,
            HttpServletResponse response
    ) {
        String refreshToken = RefreshTokenValidator.validate(refresh);
        AuthService.Tokens tokens = authService.reissue(refreshToken);
        cookie(
                response,
                tokens.refreshToken(),
                Math.max(0, Duration.between(Instant.now(), tokens.expiresAt()).toSeconds())
        );
        Map<String, Object> data = tokens.data();
        return ApiResponse.data(new ReissueResponse(
                (String) data.get("accessToken"),
                (String) data.get("tokenType")
        ));
    }

    @PostMapping("/logout")
    @Operation(summary = "로그아웃")
    public ApiResponse<Void> logout(
            @AuthenticationPrincipal AuthPrincipal principal,
            HttpServletResponse response
    ) {
        authService.logout(principal);
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
        cookie(
                response,
                tokens.refreshToken(),
                Math.max(0, Duration.between(Instant.now(), tokens.expiresAt()).toSeconds())
        );
        Map<String, Object> data = tokens.data();

        UserResponse user = toUserResponse(data.get("user"));

        return ApiResponse.data(new LoginResponse(
                (String) data.get("accessToken"),
                (String) data.get("tokenType"),
                user
        ));
    }

    private void cookie(HttpServletResponse response, String value, long age) {
        ResponseCookie cookie = ResponseCookie.from("refreshToken", value)
                .httpOnly(true)
                .secure(authProperties.secureCookie())
                .sameSite("Strict")
                .path("/api/v1/auth")
                .maxAge(age)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    }
}
