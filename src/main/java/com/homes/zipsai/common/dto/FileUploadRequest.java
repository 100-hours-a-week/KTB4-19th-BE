package com.homes.zipsai.common.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record FileUploadRequest(
        @NotBlank String originalName,
        @NotBlank String fileType,
        @NotNull @Positive Integer fileSize
) {
}
