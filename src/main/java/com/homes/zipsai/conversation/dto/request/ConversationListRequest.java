package com.homes.zipsai.conversation.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import io.swagger.v3.oas.annotations.media.Schema;

public record ConversationListRequest(
    @Schema(description = "대화 제목 검색어")
    String keyword,

    @Schema(description = "이전 응답의 nextCursor")
    String cursor,

    @Schema(description = "조회 개수 (최대 100)", defaultValue = "20")
    @Min(value = 1, message = "1 이상이어야 합니다.")
    @Max(value = 100, message = "100 이하여야 합니다.")
    Integer size
) {

    private static final int DEFAULT_SIZE = 20;

    public ConversationListRequest {
        size = size == null ? DEFAULT_SIZE : size;
    }
}
