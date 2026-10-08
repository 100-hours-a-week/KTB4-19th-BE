package com.homes.zipsai.common.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.homes.zipsai.common.dto.NotificationListResponse;
import com.homes.zipsai.common.dto.NotificationReadRequest;
import com.homes.zipsai.common.dto.NotificationReadResponse;
import com.homes.zipsai.common.dto.NotificationUnreadCountResponse;
import com.homes.zipsai.common.service.NotificationService;
import com.homes.zipsai.global.response.ApiResponse;
import com.homes.zipsai.global.security.AuthPrincipal;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@Tag(name = "민원 알림", description = "현재 입주 또는 관리 자격이 있는 사용자의 민원 알림을 조회하고 읽는다.")
@RestController
@RequestMapping("/api/v1/users/me/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @Operation(summary = "알림 목록 조회")
    @GetMapping
    public ResponseEntity<ApiResponse<NotificationListResponse>> getNotifications(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam(defaultValue = "0") @Min(value = 0, message = "0 이상이어야 합니다.") int page,
            @RequestParam(defaultValue = "20") @Min(value = 1, message = "1 이상이어야 합니다.")
            @Max(value = 100, message = "100 이하여야 합니다.") int size
    ) {
        return ResponseEntity.ok(ApiResponse.data(
            notificationService.getNotifications(principal.userId(), page, size)));
    }

    @Operation(summary = "미읽음 알림 개수 조회")
    @GetMapping("/unread-count")
    public ResponseEntity<ApiResponse<NotificationUnreadCountResponse>> getUnreadCount(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal
    ) {
        return ResponseEntity.ok(ApiResponse.data(notificationService.getUnreadCount(principal.userId())));
    }

    @Operation(summary = "알림 개별 읽음 처리")
    @PatchMapping("/{userNotiId}")
    public ResponseEntity<ApiResponse<NotificationReadResponse>> markNotificationRead(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable @Positive(message = "1 이상의 정수여야 합니다.") Long userNotiId,
            @Valid @RequestBody NotificationReadRequest request
    ) {
        return ResponseEntity.ok(ApiResponse.data(
            notificationService.markNotificationRead(principal.userId(), userNotiId)));
    }
}
