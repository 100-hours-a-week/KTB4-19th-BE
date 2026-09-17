package com.homes.zipsai.building.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** 호실 생성 2단계에서 사용자가 최종 선택한 호실 번호 목록입니다. */
@JsonIgnoreProperties(ignoreUnknown = false)
public record RoomBulkCreateRequest(List<String> roomNos) {
}
