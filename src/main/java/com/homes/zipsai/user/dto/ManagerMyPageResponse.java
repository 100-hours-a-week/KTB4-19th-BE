package com.homes.zipsai.user.dto;

public record ManagerMyPageResponse(
        Long userId,
        String userName,
        String email,
        String phone,
        Long buildingId,
        String buildingName,
        String roadAddress
) {
}
