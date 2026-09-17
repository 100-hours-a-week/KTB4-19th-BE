package com.homes.zipsai.building.dto.request;

import java.time.OffsetDateTime;
import java.util.List;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import com.homes.zipsai.conversation.ai.AiComplaintDraft;

import io.swagger.v3.oas.annotations.media.Schema;

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

    @Schema(description = "업로드 완료된 첨부 ID")
    @Size(max = 3, message = "첨부 사진은 3장 이하여야 합니다.")
    List<Long> attachmentIds
) {

    public ComplaintCreateRequest {
        location = blankToNull(location);
        symptom = blankToNull(symptom);
        attachmentIds = attachmentIds == null ? List.of() : List.copyOf(attachmentIds);
    }

    public AiComplaintDraft toDraftEdits() {
        return new AiComplaintDraft(location, symptom, occurredTime);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
