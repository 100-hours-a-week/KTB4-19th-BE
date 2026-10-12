package com.homes.zipsai.building.dto.response;

import java.util.List;

public record ManagerNearbyBuildingListResponse(
        List<ManagerNearbyBuildingResponse> buildings
) {
}
