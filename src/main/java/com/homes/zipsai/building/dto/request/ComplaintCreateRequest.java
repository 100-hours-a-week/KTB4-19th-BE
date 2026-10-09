package com.homes.zipsai.building.dto.request;

import java.time.OffsetDateTime;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import com.homes.zipsai.conversation.ai.AiComplaintDraft;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

@Builder
public record ComplaintCreateRequest(
    @Schema(description = "민원으로 전환할 대화 ID", example = "32")
    @NotNull
    @Positive(message = "1 이상의 정수여야 합니다.")
    Long conversationId,

    @Schema(description = "수정한 발생 위치", example = "302호 안방 천장")
    @Size(max = 50, message = "발생 위치는 50자 이하여야 합니다.")
    String location,

    @Schema(description = "수정한 발생 시점 (ISO-8601)", example = "2026-09-15T20:00:00+09:00")
    OffsetDateTime occurredTime,

    @Schema(description = "수정한 증상", example = "천장 가운데에서 물이 떨어짐")
    @Size(max = 100, message = "증상은 100자 이하여야 합니다.")
    String symptom,

    @Schema(description = "질의 접수 시 고른 대표 사진 ID. 없으면 첫 사진", example = "41")
    @Positive(message = "1 이상의 정수여야 합니다.")
    Long representativeAttachmentId
) {

    public AiComplaintDraft toDraftEdits() {
        return new AiComplaintDraft(location, symptom, occurredTime);
    }
}
