package com.homes.zipsai.building.dto.response;

public record ManagerRoomItemResponse(
        Long roomId,
        String roomNo,
        String roomStatus,
        String roomStatusLabel,
        String residentName
) {
}
