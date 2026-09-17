package com.homes.zipsai.conversation.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import com.homes.zipsai.conversation.dto.request.ConversationCreateRequest;
import com.homes.zipsai.conversation.dto.request.MessageSendRequest;
import com.homes.zipsai.conversation.dto.response.ConversationCreateResponse;
import com.homes.zipsai.conversation.dto.response.MessageSendResponse;
import com.homes.zipsai.conversation.service.ConversationMessageService;
import com.homes.zipsai.global.response.ApiResponse;
import com.homes.zipsai.global.security.AuthPrincipal;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@Tag(name = "입주민 AI 대화", description = "입주민이 AI 도우미와 대화하고 메시지를 주고받는다.")
@RestController
@RequestMapping("/api/v1/residents/me/conversations")
@RequiredArgsConstructor
public class ResidentConversationController {

    private final ConversationMessageService conversationMessageService;

    @Operation(summary = "대화 시작 (첫 메시지 전송)",
        description = "첫 메시지를 전송할 때 대화를 생성하고 AI 응답을 함께 반환한다. AI 호출이 실패하면 대화를 만들지 않는다.")
    @PostMapping
    public ResponseEntity<ApiResponse<ConversationCreateResponse>> createConversation(
        @Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
        @Valid @RequestBody ConversationCreateRequest request
    ) {
        ConversationCreateResponse created = conversationMessageService.createConversation(principal.userId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.data(created));
    }

    @Operation(summary = "메시지 전송",
        description = "진행 중인 대화에 메시지를 보내고 AI 응답을 함께 반환한다. 접수 정보가 모이면 SUMMARY_CARD를 반환한다.")
    @PostMapping("/{conversationId}/messages")
    public ResponseEntity<ApiResponse<MessageSendResponse>> sendMessage(
        @Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
        @PathVariable @Positive(message = "1 이상의 정수여야 합니다.") Long conversationId,
        @Valid @RequestBody MessageSendRequest request
    ) {
        MessageSendResponse sent = conversationMessageService.sendMessage(principal.userId(), conversationId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.data(sent));
    }
}
