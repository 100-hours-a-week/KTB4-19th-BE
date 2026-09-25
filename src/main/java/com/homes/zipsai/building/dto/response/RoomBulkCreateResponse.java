package com.homes.zipsai.building.dto.response;

import java.util.List;

/** 일괄 생성한 건물과 호실 결과를 API 계약에 맞춰 반환합니다. */
public record RoomBulkCreateResponse(Long buildingId, int createdCount, List<RoomResponse> rooms) {
}
