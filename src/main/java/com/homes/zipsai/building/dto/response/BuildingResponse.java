package com.homes.zipsai.building.dto.response;

/** 등록 결과로 클라이언트에 공개할 건물 식별자와 표시 정보를 담습니다. */
public record BuildingResponse(Long buildingId, String buildingName, String roadAddress) {
}
