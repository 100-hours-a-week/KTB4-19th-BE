package com.homes.zipsai.building.dto.request;

import java.util.List;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import io.swagger.v3.oas.annotations.media.Schema;

public record ResidentComplaintListRequest(
    @Schema(description = "민원 제목 검색어")
    String keyword,

    @Schema(description = "처리 상태 필터 (PENDING, IN_PROGRESS, DONE)")
    List<String> status,

    @Schema(description = "페이지 번호 (0부터)", defaultValue = "0")
    @Min(value = 0, message = "0 이상이어야 합니다.")
    Integer page,

    @Schema(description = "조회 개수 (최대 100)", defaultValue = "20")
    @Min(value = 1, message = "1 이상이어야 합니다.")
    @Max(value = 100, message = "100 이하여야 합니다.")
    Integer size
) {

    private static final int DEFAULT_PAGE = 0;
    private static final int DEFAULT_SIZE = 20;

    public ResidentComplaintListRequest {
        page = page == null ? DEFAULT_PAGE : page;
        size = size == null ? DEFAULT_SIZE : size;
    }
}
