package com.homes.zipsai.building.dto.response;

import java.time.LocalDateTime;

public record RuleDocumentResponse(
        Long documentId,
        Long attachmentId,
        String title,
        int version,
        LocalDateTime updatedAt,
        String fileUrl,
        String originalName
) {
}
