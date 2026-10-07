package com.homes.zipsai.building.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.util.StringUtils;

import io.swagger.v3.oas.annotations.media.Schema;

public record ComplaintCommentUpdateRequest(
    @Schema(description = "처리 내용 또는 QA 답변", example = "배관 교체 완료")
    @NotBlank(message = "필수 입력값입니다.")
    @Size(max = 200, message = "코멘트는 200자 이하여야 합니다.")
    String comment
) {

    public ComplaintCommentUpdateRequest {
        comment = StringUtils.hasText(comment) ? comment.strip() : null;
    }
}
