package com.homes.zipsai.conversation.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.homes.zipsai.conversation.dto.request.ConversationCreateRequest;
import com.homes.zipsai.conversation.dto.request.MessageSendRequest;
import com.homes.zipsai.conversation.dto.response.ConversationCreateResponse;
import com.homes.zipsai.conversation.dto.response.ConversationListResponse;
import com.homes.zipsai.conversation.dto.response.ConversationMessagesResponse;
import com.homes.zipsai.conversation.dto.response.MessageSendResponse;
import com.homes.zipsai.conversation.service.ConversationMessageService;
import com.homes.zipsai.conversation.service.ConversationService;
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

    private final ConversationService conversationService;
    private final ConversationMessageService conversationMessageService;

    @Operation(summary = "대화 목록 조회",
        description = "본인 대화를 최근 메시지 시각 내림차순으로 size개 반환한다. 다음 목록은 nextCursor로 조회한다.")
    @GetMapping
    public ResponseEntity<ApiResponse<ConversationListResponse>> getConversations(
        @Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
        @Parameter(description = "대화 제목 검색어") @RequestParam(required = false) String keyword,
        @Parameter(description = "이전 응답의 nextCursor") @RequestParam(required = false) String cursor,
        @Parameter(description = "조회 개수 (최대 100)")
        @RequestParam(defaultValue = "20") @Min(value = 1, message = "1 이상이어야 합니다.")
        @Max(value = 100, message = "100 이하여야 합니다.") int size
    ) {
        return ResponseEntity.ok(
            ApiResponse.data(conversationService.getConversations(principal.userId(), keyword, cursor, size)));
    }

    @Operation(summary = "대화 시작 (첫 메시지 전송)",
        description = "첫 메시지를 전송할 때 대화를 생성하고 AI 응답을 함께 반환한다. AI 호출이 실패하면 대화를 만들지 않는다.")
    @PostMapping
    public ResponseEntity<ApiResponse<ConversationCreateResponse>> createConversation(
        @Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
        @Valid @RequestBody ConversationCreateRequest request
    ) {
        ConversationCreateResponse created = conversationMessageService.createConversation(
            principal.userId(), request.content(), request.attachmentIds());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.data(created));
    }

    @Operation(summary = "채팅방 메세지 조회",
        description = "최신 메시지부터 size개를 오래된 순으로 반환한다. 더 과거 메시지는 nextCursor로 조회한다.")
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
        return ResponseEntity.ok(
            ApiResponse.data(conversationService.getMessages(principal.userId(), conversationId, cursor, size)));
    }

    @Operation(summary = "메시지 전송",
        description = "진행 중인 대화에 메시지를 보내고 AI 응답을 함께 반환한다. 접수 정보가 모이면 SUMMARY_CARD를 반환한다.")
    @PostMapping("/{conversationId}/messages")
    public ResponseEntity<ApiResponse<MessageSendResponse>> sendMessage(
        @Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
        @PathVariable @Positive(message = "1 이상의 정수여야 합니다.") Long conversationId,
        @Valid @RequestBody MessageSendRequest request
    ) {
        MessageSendResponse sent = conversationMessageService.sendMessage(
            principal.userId(), conversationId, request.content(), request.attachmentIds());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.data(sent));
    }
}
