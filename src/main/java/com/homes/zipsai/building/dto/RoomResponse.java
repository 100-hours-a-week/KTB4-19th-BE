package com.homes.zipsai.building.dto;

/** 생성 결과에 포함되는 호실 식별자, 번호, 현재 상태입니다. */
public record RoomResponse(Long roomId, String roomNo, String roomStatus) {
}
