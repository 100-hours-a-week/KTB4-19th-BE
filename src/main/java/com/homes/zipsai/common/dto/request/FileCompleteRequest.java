package com.homes.zipsai.common.dto.request;

import jakarta.validation.constraints.NotBlank;

public record FileCompleteRequest(@NotBlank String fileStatus) {
}
