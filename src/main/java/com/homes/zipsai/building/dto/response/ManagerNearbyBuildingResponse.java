package com.homes.zipsai.building.dto.response;

public record ManagerNearbyBuildingResponse(
        Long buildingId,
        String buildingName,
        double latitude,
        double longitude,
        NearbyRoomSummaryResponse roomSummary
) {
}
