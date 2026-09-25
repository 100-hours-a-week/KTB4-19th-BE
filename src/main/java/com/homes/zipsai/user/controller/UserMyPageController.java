package com.homes.zipsai.user.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.homes.zipsai.global.response.ApiResponse;
import com.homes.zipsai.global.security.AuthPrincipal;
import com.homes.zipsai.user.dto.response.ManagerMyPageResponse;
import com.homes.zipsai.user.dto.response.ResidentMyPageResponse;
import com.homes.zipsai.user.service.UserService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@Tag(name = "마이페이지", description = "관리자와 입주민의 전용 프로필을 조회한다.")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1")
public class UserMyPageController {

    private final UserService userService;

    @Operation(summary = "관리자 마이페이지 조회")
    @GetMapping("/managers/me")
    public ResponseEntity<ApiResponse<ManagerMyPageResponse>> getManagerMyPage(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal
    ) {
        return ResponseEntity.ok(ApiResponse.data(userService.getManagerMyPage(principal.userId())));
    }

    @Operation(summary = "입주민 마이페이지 조회")
    @GetMapping("/residents/me")
    public ResponseEntity<ApiResponse<ResidentMyPageResponse>> getResidentMyPage(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal
    ) {
        return ResponseEntity.ok(ApiResponse.data(userService.getResidentMyPage(principal.userId())));
    }
}
