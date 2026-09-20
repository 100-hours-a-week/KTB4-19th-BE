package com.homes.zipsai.building.dto.response;

public record ManagerComplaintSummaryResponse(
        long pendingCount,
        long inProgressCount,
        long weeklyDoneCount,
        long totalCount
) {
}
