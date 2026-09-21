package com.homes.zipsai.building.dto.response;

import java.time.LocalDateTime;

public record ManagerBuildingDetailResponse(
        Long buildingId,
        String buildingName,
        String roadAddress,
        long totalRoomCount,
        LocalDateTime updatedAt
) {
}
