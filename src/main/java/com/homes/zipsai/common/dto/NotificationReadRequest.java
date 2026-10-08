package com.homes.zipsai.common.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;

public record NotificationReadRequest(
    @NotNull(message = "필수 입력값입니다.")
    @AssertTrue(message = "true여야 합니다.")
    Boolean isRead
) {
}
