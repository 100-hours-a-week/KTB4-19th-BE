package com.homes.zipsai.building.dto.response;

public record ManagerRoomSummaryResponse(
        long livingCount,
        long invitedCount,
        long emptyCount,
        long totalCount
) {
}
