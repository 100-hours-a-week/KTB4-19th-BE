package com.homes.zipsai.building.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = false)
public record BuildingRegistrationRequest(
        @Size(max = 20, message = "건물명은 20자 이하여야 합니다.") String buildingName,
        @NotBlank(message = "필수 입력값입니다.")
        @Size(max = 200, message = "주소는 200자 이하여야 합니다.") String roadAddress
) {
    public BuildingRegistrationRequest {
        buildingName = buildingName == null || buildingName.isBlank()
                ? null
                : buildingName.trim();
        roadAddress = roadAddress == null ? null : roadAddress.trim();
    }
}
