package com.homes.zipsai.building.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.homes.zipsai.building.dto.RoomBulkCreateRequest;
import com.homes.zipsai.building.dto.RoomBulkCreateResponse;
import com.homes.zipsai.building.service.RoomService;
import com.homes.zipsai.global.response.ApiResponse;
import com.homes.zipsai.global.security.AuthPrincipal;

import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/managers/me/buildings/{buildingId}/rooms")
public class RoomController {
    private final RoomService rooms;

    /** 화면에서 선택한 호실 번호만 해당 건물에 일괄 등록합니다. */
    @PostMapping
    public ResponseEntity<ApiResponse<RoomBulkCreateResponse>> create(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable long buildingId,
            @RequestBody RoomBulkCreateRequest request
    ) {
        return ResponseEntity.status(201)
                .body(ApiResponse.data(rooms.create(principal, buildingId, request)));
    }
}
