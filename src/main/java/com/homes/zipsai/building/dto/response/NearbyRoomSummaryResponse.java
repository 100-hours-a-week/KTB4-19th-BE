package com.homes.zipsai.building.dto.response;

public record NearbyRoomSummaryResponse(
        long totalCount,
        long livingCount,
        long emptyCount
) {
}
