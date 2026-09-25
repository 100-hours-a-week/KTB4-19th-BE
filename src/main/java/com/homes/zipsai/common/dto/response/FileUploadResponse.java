package com.homes.zipsai.common.dto.response;

import java.util.Map;

public record FileUploadResponse(
        Long attachmentId,
        String uploadUrl,
        int expiresIn,
        Map<String, String> requiredHeaders
) {
}
