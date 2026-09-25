package com.homes.zipsai.user.dto.request;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.homes.zipsai.auth.dto.request.AgreementRequest;
import com.homes.zipsai.user.domain.UserRole;
import com.homes.zipsai.user.validator.UserInput;

@JsonIgnoreProperties(ignoreUnknown = false)
public record UserPatchRequest(
    UserRole userRole,
    @Size(min = 1, max = 7, message = "이름은 1~7자여야 합니다.") String userName,
    @Pattern(regexp = UserInput.PHONE_PATTERN, message = "연락처 형식이 올바르지 않습니다.") String phone,
    List<@Valid AgreementRequest> agreements
) {
    public UserPatchRequest {
        if (userName != null) {
            userName = userName.trim();
        }
    }
}
