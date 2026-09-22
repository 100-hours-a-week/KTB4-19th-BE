package com.homes.zipsai.building.dto;

import java.time.LocalDateTime;

public record RuleDocumentResponse(
        Long documentId,
        Long attachmentId,
        String title,
        int version,
        LocalDateTime updatedAt
) {
}
