package com.homes.zipsai.building.dto.response;

import java.util.List;

public record ManagerRoomListResponse(
        Long buildingId,
        String buildingName,
        long totalCount,
        List<ManagerRoomItemResponse> rooms
) {
}
