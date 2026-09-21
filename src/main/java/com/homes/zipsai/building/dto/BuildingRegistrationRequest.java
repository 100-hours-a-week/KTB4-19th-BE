package com.homes.zipsai.building.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** 건물 등록 입력값. 건물명은 생략할 수 있고 도로명 주소는 필수입니다. */
// 정의되지 않은 필드가 들어온 요청은 조용히 무시하지 않고 역직렬화 오류로 처리합니다.
@JsonIgnoreProperties(ignoreUnknown = false)
public record BuildingRegistrationRequest(String buildingName, String roadAddress) {
}
