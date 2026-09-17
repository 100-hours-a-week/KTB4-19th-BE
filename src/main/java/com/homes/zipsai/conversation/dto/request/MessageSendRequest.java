package com.homes.zipsai.conversation.dto.request;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

public record MessageSendRequest(
    @Schema(description = "메시지 (앞뒤 공백 제거 후 200자 이하)", example = "안방 천장 가운데요")
    @NotBlank
    @Size(max = 200, message = "메시지는 200자 이하여야 합니다.")
    String content,

    @Schema(description = "업로드 완료된 첨부 ID (최대 3개)")
    @Size(max = 3, message = "첨부 사진은 3장 이하여야 합니다.")
    List<Long> attachmentIds
) {

    public MessageSendRequest {
        content = content == null || content.isBlank() ? null : content.strip();
        attachmentIds = attachmentIds == null ? List.of() : List.copyOf(attachmentIds);
    }
}
