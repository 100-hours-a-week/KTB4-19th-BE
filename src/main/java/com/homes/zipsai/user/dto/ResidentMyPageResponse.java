package com.homes.zipsai.user.dto;

public record ResidentMyPageResponse(
        Long userId,
        String userName,
        String email,
        String phone,
        Long roomId,
        String buildingName,
        String roomNo,
        String managerName,
        String managerPhone
) {
}
