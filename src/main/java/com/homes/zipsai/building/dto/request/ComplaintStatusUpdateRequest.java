package com.homes.zipsai.building.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record ComplaintStatusUpdateRequest(
    @NotBlank(message = "필수 입력값입니다.")
    @Pattern(regexp = "PENDING|IN_PROGRESS|DONE", message = "허용되지 않은 상태값입니다.")
    String statusCode
) {

    public ComplaintStatusUpdateRequest {
        statusCode = statusCode == null || statusCode.isBlank() ? null : statusCode.strip();
    }
}
