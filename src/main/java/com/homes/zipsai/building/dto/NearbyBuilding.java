package com.homes.zipsai.building.dto;

public record NearbyBuilding(
        Long buildingId,
        String buildingName,
        double latitude,
        double longitude
) {
}
