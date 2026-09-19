package com.homes.zipsai.conversation.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import io.swagger.v3.oas.annotations.media.Schema;

public record ConversationStatusUpdateRequest(
    @Schema(description = "변경할 대화 상태", example = "RESOLVED", allowableValues = "RESOLVED")
    @NotBlank
    @Pattern(regexp = "RESOLVED", message = "허용되지 않은 상태값입니다.")
    String conversationStatus
) {

    public ConversationStatusUpdateRequest {
        if (conversationStatus != null) {
            conversationStatus = conversationStatus.isBlank() ? null : conversationStatus.strip();
        }
    }
}
