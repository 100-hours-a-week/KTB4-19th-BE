package com.homes.zipsai.conversation.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;

import io.swagger.v3.oas.annotations.media.Schema;

public record MessageListRequest(
    @Schema(description = "마지막으로 조회한 가장 오래된 messageId")
    @Positive(message = "1 이상의 정수여야 합니다.")
    Long cursor,

    @Schema(description = "조회 개수 (최대 100)", defaultValue = "20")
    @Min(value = 1, message = "1 이상이어야 합니다.")
    @Max(value = 100, message = "100 이하여야 합니다.")
    Integer size
) {

    private static final int DEFAULT_SIZE = 20;

    public MessageListRequest {
        size = size == null ? DEFAULT_SIZE : size;
    }
}
