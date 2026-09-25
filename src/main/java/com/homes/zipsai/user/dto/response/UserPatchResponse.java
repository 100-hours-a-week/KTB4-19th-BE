package com.homes.zipsai.user.dto.response;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.homes.zipsai.user.domain.UserRole;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record UserPatchResponse(
        Long userId,
        UserRole userRole,
        String userName,
        String phone,
        List<UserAgreementResponse> agreements,
        String accessToken,
        String tokenType
) {
}
