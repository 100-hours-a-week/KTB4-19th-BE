package com.homes.zipsai.conversation.controller;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.homes.zipsai.conversation.dto.response.ConversationMessagesResponse;
import com.homes.zipsai.conversation.service.ConversationService;
import com.homes.zipsai.global.response.ApiResponse;
import com.homes.zipsai.global.security.AuthPrincipal;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@Tag(name = "관리자 AI 대화", description = "관리자가 담당 건물의 민원이 접수된 대화를 읽는다.")
@RestController
@RequestMapping("/api/v1/managers/me/conversations")
@RequiredArgsConstructor
public class ManagerConversationController {

    private final ConversationService conversationService;

    @Operation(summary = "민원 원본 대화 조회",
        description = "민원이 접수된 대화를 읽기 전용으로 조회한다. 담당 건물의 민원에 연결된 대화만 조회할 수 있다.")
    @GetMapping("/{conversationId}/messages")
    public ResponseEntity<ApiResponse<ConversationMessagesResponse>> getMessages(
        @Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
        @PathVariable @Positive(message = "1 이상의 정수여야 합니다.") Long conversationId,
        @Parameter(description = "마지막으로 조회한 가장 오래된 messageId")
        @RequestParam(required = false) @Positive(message = "1 이상의 정수여야 합니다.") Long cursor,
        @Parameter(description = "조회 개수 (최대 100)")
        @RequestParam(defaultValue = "20") @Min(value = 1, message = "1 이상이어야 합니다.")
        @Max(value = 100, message = "100 이하여야 합니다.") int size
    ) {
        return ResponseEntity.ok(ApiResponse.data(
            conversationService.getComplaintMessagesForManager(principal.userId(), conversationId, cursor, size)));
    }
}
