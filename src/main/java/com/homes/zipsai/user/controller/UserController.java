package com.homes.zipsai.user.controller;

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
import com.homes.zipsai.user.dto.EmailAvailabilityResponse;
import com.homes.zipsai.user.dto.UserPatchRequest;
import com.homes.zipsai.user.dto.UserPatchResponse;
import com.homes.zipsai.user.dto.UserProfileResponse;
import com.homes.zipsai.user.dto.OnboardingStatusResponse;
import com.homes.zipsai.user.service.UserService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService service;

    @GetMapping("/email-availability")
    public ResponseEntity<ApiResponse<EmailAvailabilityResponse>> available(
            @RequestParam(required = false) String email
    ) {
        return ResponseEntity.ok(ApiResponse.data(service.checkEmailAvailability(email)));
    }

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<UserProfileResponse>> me(
            @AuthenticationPrincipal AuthPrincipal principal
    ) {
        return ResponseEntity.ok(ApiResponse.data(service.me(principal.userId())));
    }

    @GetMapping("/me/onboarding-status")
    public ResponseEntity<ApiResponse<OnboardingStatusResponse>> onboardingStatus(
            @AuthenticationPrincipal AuthPrincipal principal
    ) {
        return ResponseEntity.ok(ApiResponse.data(service.onboardingStatus(principal)));
    }

    // V3_P2: NONE에서 최초 한 번만 역할을 선택한다.
    @PatchMapping("/me")
    public ResponseEntity<ApiResponse<UserPatchResponse>> patch(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestBody UserPatchRequest request
    ) {
        return ResponseEntity.ok(ApiResponse.data(service.patch(principal, request)));
    }
}
