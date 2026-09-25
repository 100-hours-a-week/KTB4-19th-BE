package com.homes.zipsai.building.controller;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.homes.zipsai.building.dto.request.BuildingRegistrationRequest;
import com.homes.zipsai.building.dto.response.BuildingResponse;
import com.homes.zipsai.building.dto.response.ManagerBuildingDetailResponse;
import com.homes.zipsai.building.dto.response.ManagerComplaintSummaryResponse;
import com.homes.zipsai.building.dto.response.ManagerRoomListResponse;
import com.homes.zipsai.building.dto.response.ManagerRoomSummaryResponse;
import com.homes.zipsai.building.service.BuildingService;
import com.homes.zipsai.global.response.ApiResponse;
import com.homes.zipsai.global.security.AuthPrincipal;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@Tag(name = "관리자 건물", description = "관리자가 관리하는 건물과 호실을 조회한다.")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/managers/me/building")
public class BuildingController {
    private final BuildingService buildingService;

    @PostMapping
    public ResponseEntity<ApiResponse<BuildingResponse>> register(
            @AuthenticationPrincipal AuthPrincipal principal,
        @Valid @RequestBody BuildingRegistrationRequest request
    ) {
        return ResponseEntity.status(201)
                .body(ApiResponse.data(buildingService.register(
                        principal.userId(), request.buildingName(), request.roadAddress())));
    }

    @Operation(summary = "건물 상세 조회", description = "관리자가 관리 대상 건물의 상세 정보와 호실 수를 조회한다.")
    @GetMapping
    public ResponseEntity<ApiResponse<ManagerBuildingDetailResponse>> getBuilding(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal
    ) {
        return ResponseEntity.ok(ApiResponse.data(buildingService.getBuilding(principal.userId())));
    }

    @Operation(summary = "호실 현황 요약 조회", description = "관리자가 관리 대상 건물의 입주·초대·공실 현황을 조회한다.")
    @GetMapping("/rooms/summary")
    public ResponseEntity<ApiResponse<ManagerRoomSummaryResponse>> getRoomSummary(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal
    ) {
        return ResponseEntity.ok(ApiResponse.data(buildingService.getRoomSummary(principal.userId())));
    }

    @Operation(summary = "호실 목록 조회", description = "관리자가 관리 대상 건물의 전체 호실과 입주민 정보를 조회한다.")
    @GetMapping("/rooms")
    public ResponseEntity<ApiResponse<ManagerRoomListResponse>> getRooms(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal
    ) {
        return ResponseEntity.ok(ApiResponse.data(buildingService.getRooms(principal.userId())));
    }

    @Operation(summary = "민원 처리 상태 요약 조회",
        description = "관리자가 관리 대상 건물의 민원 처리 상태별 건수를 조회한다.", tags = {"관리자 민원"})
    @GetMapping("/complaints/summary")
    public ResponseEntity<ApiResponse<ManagerComplaintSummaryResponse>> getComplaintSummary(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal
    ) {
        return ResponseEntity.ok(ApiResponse.data(buildingService.getComplaintSummary(principal.userId())));
    }
}
