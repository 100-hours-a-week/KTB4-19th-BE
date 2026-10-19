package com.homes.zipsai.auth.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = false)
public record PasswordChangeRequest(
    String currentPassword,
    String newPassword,
    String newPasswordConfirm
) {
}
