package com.homes.zipsai.conversation.dto.request;

import java.util.List;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.fasterxml.jackson.annotation.JsonIgnore;

import io.swagger.v3.oas.annotations.media.Schema;

public record ConversationCreateRequest(
    @Schema(description = "첫 메시지 (200자 이하, 사진이 있으면 비워도 됨)", example = "천장에서 물이 새요")
    @Size(max = 200, message = "메시지는 200자 이하여야 합니다.")
    String content,

    @Schema(description = "업로드 완료된 사진 첨부 ID (최대 3개)")
    @Size(max = 3, message = "첨부 사진은 3장 이하여야 합니다.")
    List<@NotNull Long> attachmentIds
) {

    public ConversationCreateRequest {
        content = content == null ? "" : content.strip();
    }

    @JsonIgnore
    @AssertTrue(message = "메시지나 사진 중 하나는 보내야 합니다.")
    public boolean isContentOrImagePresent() {
        return !content.isEmpty() || (attachmentIds != null && !attachmentIds.isEmpty());
    }
}
