package com.homes.zipsai.common.dto;

import jakarta.validation.constraints.NotBlank;

public record FileCompleteRequest(@NotBlank String fileStatus) {
}
