package com.homes.zipsai.building.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.homes.zipsai.building.dto.BuildingRegistrationRequest;
import com.homes.zipsai.building.dto.BuildingResponse;
import com.homes.zipsai.building.service.BuildingService;
import com.homes.zipsai.global.response.ApiResponse;
import com.homes.zipsai.global.security.AuthPrincipal;

import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
// 건물 등록 API는 건물 관리자 본인의 계정 경로 아래에 둡니다.
@RequestMapping("/api/v1/managers/me/buildings")
public class BuildingController {
    private final BuildingService buildings;

    /** 로그인한 관리자의 인증 정보와 요청 본문을 서비스에 전달합니다. */
    @PostMapping
    public ResponseEntity<ApiResponse<BuildingResponse>> register(
            // JWT 인증 필터가 현재 요청의 사용자 정보를 principal로 넣어 줍니다.
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestBody BuildingRegistrationRequest request
    ) {
        // 최초 등록 성공을 나타내는 HTTP 201과 공통 응답 형식을 반환합니다.
        return ResponseEntity.status(201)
                .body(ApiResponse.data(buildings.register(principal, request)));
    }
}
