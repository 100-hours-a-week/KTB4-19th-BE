package com.homes.zipsai.building.controller;

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

@RestController
@RequestMapping("/api/v1/managers/me")
public class RoomController {
    private final RoomService roomService;

    public RoomController(RoomService roomService) {
        this.roomService = roomService;
    }

    @PostMapping("/building/rooms")
    public ResponseEntity<ApiResponse<RoomBulkCreateResponse>> create(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestBody RoomBulkCreateRequest request
    ) {
        return ResponseEntity.status(201)
                .body(ApiResponse.data(roomService.create(principal, request)));
    }

    @DeleteMapping("/rooms/{roomId}/resident")
    public ResponseEntity<ApiResponse<Void>> moveOut(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable Long roomId
    ) {
        roomService.moveOutResident(principal.userId(), roomId);
        return ResponseEntity.ok(ApiResponse.data(null));
    }
}
