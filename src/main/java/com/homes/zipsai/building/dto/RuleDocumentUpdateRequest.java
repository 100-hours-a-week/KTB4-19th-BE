package com.homes.zipsai.building.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RuleDocumentUpdateRequest(
        @NotBlank @Size(max = 20) String title
) {
}
