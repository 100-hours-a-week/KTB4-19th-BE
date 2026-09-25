package com.homes.zipsai.auth.dto.request;

import java.util.List;
import java.util.Locale;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.homes.zipsai.user.validator.UserInput;

@JsonIgnoreProperties(ignoreUnknown = false)
public record SignupRequest(
    @NotBlank
    @Pattern(regexp = UserInput.EMAIL_PATTERN, message = "이메일 형식이 올바르지 않습니다.") String email,
    @NotBlank
    @Pattern(regexp = UserInput.PASSWORD_PATTERN, message = "비밀번호 형식이 올바르지 않습니다.") String password,
    String passwordConfirm,
    @Size(min = 1, max = 7, message = "이름은 1~7자여야 합니다.") String userName,
    @Pattern(regexp = UserInput.PHONE_PATTERN, message = "연락처 형식이 올바르지 않습니다.") String phone,
    @NotNull List<@Valid AgreementRequest> agreements
) {
    public SignupRequest {
        if (email != null) {
            email = email.trim().toLowerCase(Locale.ROOT);
            if (email.isBlank()) {
                email = null;
            }
        }
        if (password != null && password.isBlank()) {
            password = null;
        }
        if (userName != null) {
            userName = userName.trim();
        }
    }
}
