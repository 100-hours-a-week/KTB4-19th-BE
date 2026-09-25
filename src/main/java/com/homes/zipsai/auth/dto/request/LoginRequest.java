package com.homes.zipsai.auth.dto.request;

import java.util.Locale;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import com.homes.zipsai.user.validator.UserInput;

public record LoginRequest(
    @NotBlank
    @Pattern(regexp = UserInput.EMAIL_PATTERN, message = "이메일 형식이 올바르지 않습니다.") String email,
    @NotBlank
    @Pattern(regexp = UserInput.PASSWORD_PATTERN, message = "비밀번호 형식이 올바르지 않습니다.") String password
) {
    public LoginRequest {
        if (email != null) {
            email = email.trim().toLowerCase(Locale.ROOT);
            if (email.isBlank()) {
                email = null;
            }
        }
        if (password != null && password.isBlank()) {
            password = null;
        }
    }
}
