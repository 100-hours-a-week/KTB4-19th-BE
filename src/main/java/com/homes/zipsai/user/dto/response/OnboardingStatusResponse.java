package com.homes.zipsai.user.dto.response;

import com.homes.zipsai.user.domain.UserRole;

public record OnboardingStatusResponse(
        UserRole userRole,
        Long buildingId,
        boolean hasRooms,
        boolean residentConnected,
        String nextStep
) {
}
