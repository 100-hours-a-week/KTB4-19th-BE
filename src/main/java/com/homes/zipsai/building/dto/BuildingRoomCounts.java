package com.homes.zipsai.building.dto;

public record BuildingRoomCounts(
        Long buildingId,
        long totalCount,
        long livingCount,
        long emptyCount
) {
}
