package com.homes.zipsai.building.dto;

import java.util.List;

public record RoomBulkCreateResponse(Long buildingId, int createdCount, List<RoomResponse> rooms) {
}
