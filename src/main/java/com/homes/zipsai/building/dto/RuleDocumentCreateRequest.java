package com.homes.zipsai.building.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record RuleDocumentCreateRequest(
        @NotNull Long attachmentId,
        @Size(min = 1, max = 20) String title
) {
}
