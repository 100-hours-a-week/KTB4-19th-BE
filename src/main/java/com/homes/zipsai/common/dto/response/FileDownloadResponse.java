package com.homes.zipsai.common.dto.response;

public record FileDownloadResponse(
        Long attachmentId,
        String originalName,
        int fileSize,
        String fileType,
        String fileStatus,
        String downloadUrl,
        int expiresInSeconds
) {
}
