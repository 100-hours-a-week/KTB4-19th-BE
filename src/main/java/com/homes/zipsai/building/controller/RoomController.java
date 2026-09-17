package com.homes.zipsai.building.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.homes.zipsai.building.service.RoomService;
import com.homes.zipsai.global.response.ApiResponse;
import com.homes.zipsai.global.security.AuthPrincipal;

@RestController
@RequestMapping("/api/v1/managers/me/rooms")
public class RoomController {
    private final RoomService roomService;

    public RoomController(RoomService roomService) {
        this.roomService = roomService;
    }

    @DeleteMapping("/{roomId}/resident")
    public ResponseEntity<ApiResponse<Void>> moveOut(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable Long roomId
    ) {
        roomService.moveOutResident(getAuthenticatedUserId(principal), roomId);
        return ResponseEntity.ok(ApiResponse.data(null));
    }

    private Long getAuthenticatedUserId(AuthPrincipal principal) {
        return principal.userId();
    }
}
