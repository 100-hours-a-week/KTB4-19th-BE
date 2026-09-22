package com.homes.zipsai.building.controller;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
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
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1/managers/me")
@Tag(name = "관리자 호실", description = "관리자 건물의 호실을 일괄 등록하고 입주민을 퇴거 처리한다.")
public class RoomController {
    private final RoomService roomService;

    public RoomController(RoomService roomService) {
        this.roomService = roomService;
    }

    @PostMapping("/building/rooms")
    @Operation(summary = "호실 일괄 등록")
    public ResponseEntity<ApiResponse<RoomBulkCreateResponse>> create(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody RoomBulkCreateRequest roomBulkCreateRequest
    ) {
        return ResponseEntity.status(201)
                .body(ApiResponse.data(roomService.create(principal.userId(), roomBulkCreateRequest.roomNos())));
    }

    @DeleteMapping("/rooms/{roomId}/resident")
    @Operation(summary = "입주민 퇴거 처리")
    public ResponseEntity<ApiResponse<Void>> moveOut(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable Long roomId
    ) {
        roomService.moveOutResident(principal.userId(), roomId);
        return ResponseEntity.ok(ApiResponse.data(null));
    }
}
