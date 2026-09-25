package com.homes.zipsai.building.dto.request;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** 호실 생성 2단계에서 사용자가 최종 선택한 호실 번호 목록입니다. */
@JsonIgnoreProperties(ignoreUnknown = false)
public record RoomBulkCreateRequest(
        @NotNull(message = "필수 입력값입니다.")
        @Size(min = 1, message = "호실을 하나 이상 선택해야 합니다.")
        List<@NotBlank(message = "호실 번호는 비워둘 수 없습니다.") String> roomNos
) {
}
