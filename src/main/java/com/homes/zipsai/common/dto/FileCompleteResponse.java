package com.homes.zipsai.common.dto;

public record FileCompleteResponse(
        Long attachmentId,
        String fileKey,
        String originalName,
        int fileSize,
        String fileType,
        String fileStatus
) {
}
