package com.homes.zipsai.building.controller;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.homes.zipsai.building.dto.InvitationCodeResponse;
import com.homes.zipsai.building.dto.InvitationCodeValidationResponse;
import com.homes.zipsai.building.dto.RoomConnectionRequest;
import com.homes.zipsai.building.dto.RoomConnectionResponse;
import com.homes.zipsai.building.service.InvitationCodeService;
import com.homes.zipsai.building.service.RoomConnectionService;
import com.homes.zipsai.global.response.ApiResponse;
import com.homes.zipsai.global.security.AuthPrincipal;

@RestController
@RequestMapping("/api/v1")
public class InvitationCodeController {
    private final InvitationCodeService invitationCodeService;
    private final RoomConnectionService roomConnectionService;

    public InvitationCodeController(
            InvitationCodeService service,
            RoomConnectionService roomConnectionService
    ) {
        this.invitationCodeService = service;
        this.roomConnectionService = roomConnectionService;
    }

    @PostMapping("/managers/me/rooms/{roomId}/invitation-codes")
    public ResponseEntity<ApiResponse<InvitationCodeResponse>> issue(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable Long roomId
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.data(
                        invitationCodeService.issueOrReissue(getAuthenticatedUserId(principal), roomId)));
    }

    @GetMapping("/residents/me/invitation-codes/{code}")
    public ResponseEntity<ApiResponse<InvitationCodeValidationResponse>> validate(@PathVariable String code) {
        return ResponseEntity.ok(ApiResponse.data(invitationCodeService.validateInvitationCode(code)));
    }

    @PutMapping("/residents/me/room")
    public ResponseEntity<ApiResponse<RoomConnectionResponse>> connect(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody RoomConnectionRequest request
    ) {
        return ResponseEntity.ok(ApiResponse.data(
                roomConnectionService.connectRoom(getAuthenticatedUserId(principal), request.code())));
    }

    @DeleteMapping("/managers/me/rooms/{roomId}/invitation-codes/{codeId}")
    public ResponseEntity<ApiResponse<Void>> cancel(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable Long roomId,
            @PathVariable Long codeId
    ) {
        invitationCodeService.cancelInvitation(getAuthenticatedUserId(principal), roomId, codeId);
        return ResponseEntity.ok(ApiResponse.data(null));
    }

    private Long getAuthenticatedUserId(AuthPrincipal principal) {
        return principal.userId();
    }
}
