package com.homes.zipsai.user.dto;

import java.util.List;

import com.homes.zipsai.user.domain.UserRole;

public record UserProfileResponse(
        Long userId,
        String email,
        UserRole userRole,
        Long buildingId,
        Long roomId,
        String userName,
        String phone,
        List<UserAgreementResponse> agreements
) {
}
