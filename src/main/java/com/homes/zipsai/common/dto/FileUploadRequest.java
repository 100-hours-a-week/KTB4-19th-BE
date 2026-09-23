package com.homes.zipsai.common.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import com.homes.zipsai.common.domain.FilePurpose;

import io.swagger.v3.oas.annotations.media.Schema;

public record FileUploadRequest(
        @NotBlank String originalName,
        @NotBlank String fileType,
        @NotNull @Positive Integer fileSize,

        @Schema(description = "파일 용도. 저장 경로를 나눈다.", example = "CONVERSATION")
        @NotNull FilePurpose purpose
) {
}
