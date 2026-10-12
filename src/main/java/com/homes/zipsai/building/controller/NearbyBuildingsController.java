package com.homes.zipsai.building.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.homes.zipsai.building.dto.response.ManagerNearbyBuildingListResponse;
import com.homes.zipsai.building.service.NearbyBuildingService;
import com.homes.zipsai.global.response.ApiResponse;
import com.homes.zipsai.global.security.AuthPrincipal;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@Tag(name = "주변 건물", description = "주변 건물 정보를 조회한다.")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1")
public class NearbyBuildingsController {

    private final NearbyBuildingService nearbyBuildingService;

    @Operation(summary = "주변 건물 입주 현황 조회",
            description = "지도 범위 안의 등록 건물을 조회한다. 초대중 호실은 공실에 포함한다.")
    @GetMapping("/managers/me/buildings/nearby")
    public ResponseEntity<ApiResponse<ManagerNearbyBuildingListResponse>> getNearbyBuildings(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam Double minLatitude,
            @RequestParam Double maxLatitude,
            @RequestParam Double minLongitude,
            @RequestParam Double maxLongitude
    ) {
        return ResponseEntity.ok(ApiResponse.data(nearbyBuildingService.getNearbyBuildings(
                principal.userId(), minLatitude, maxLatitude, minLongitude, maxLongitude)));
    }
}
