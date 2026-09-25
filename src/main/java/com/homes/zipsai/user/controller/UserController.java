package com.homes.zipsai.user.controller;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.homes.zipsai.global.response.ApiResponse;
import com.homes.zipsai.global.security.AuthPrincipal;
import com.homes.zipsai.user.dto.request.UserPatchRequest;
import com.homes.zipsai.user.dto.response.EmailAvailabilityResponse;
import com.homes.zipsai.user.dto.response.OnboardingStatusResponse;
import com.homes.zipsai.user.dto.response.UserPatchResponse;
import com.homes.zipsai.user.dto.response.UserProfileResponse;
import com.homes.zipsai.user.service.UserService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
@Tag(name = "사용자", description = "사용자 프로필과 온보딩 상태를 조회·변경한다.")
public class UserController {

    private final UserService service;

    @GetMapping("/email-availability")
    @Operation(summary = "이메일 중복 확인")
    public ResponseEntity<ApiResponse<EmailAvailabilityResponse>> available(
            @RequestParam(required = false) String email
    ) {
        return ResponseEntity.ok(ApiResponse.data(service.checkEmailAvailability(email)));
    }

    @GetMapping("/me")
    @Operation(summary = "내 프로필 조회")
    public ResponseEntity<ApiResponse<UserProfileResponse>> me(
            @AuthenticationPrincipal AuthPrincipal principal
    ) {
        return ResponseEntity.ok(ApiResponse.data(service.me(principal.userId())));
    }

    @GetMapping("/me/onboarding-status")
    @Operation(summary = "온보딩 상태 조회", description = "역할, 건물, 호실 연결 상태에 따라 다음 화면을 반환한다.")
    public ResponseEntity<ApiResponse<OnboardingStatusResponse>> onboardingStatus(
            @AuthenticationPrincipal AuthPrincipal principal
    ) {
        return ResponseEntity.ok(ApiResponse.data(service.onboardingStatus(principal)));
    }

    // V3_P2: NONE에서 최초 한 번만 역할을 선택한다.
    @PatchMapping("/me")
    @Operation(summary = "내 프로필·역할 변경")
    public ResponseEntity<ApiResponse<UserPatchResponse>> patch(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody UserPatchRequest userPatchRequest
    ) {
        return ResponseEntity.ok(ApiResponse.data(service.patch(
                principal, userPatchRequest.userRole(), userPatchRequest.userName(), userPatchRequest.phone(), userPatchRequest.agreements())));
    }
}
