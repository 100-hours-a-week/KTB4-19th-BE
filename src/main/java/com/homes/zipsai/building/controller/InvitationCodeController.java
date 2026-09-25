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

import com.homes.zipsai.building.dto.request.RoomConnectionRequest;
import com.homes.zipsai.building.dto.response.InvitationCodeResponse;
import com.homes.zipsai.building.dto.response.InvitationCodeValidationResponse;
import com.homes.zipsai.building.dto.response.RoomConnectionResponse;
import com.homes.zipsai.building.service.InvitationCodeService;
import com.homes.zipsai.building.service.RoomConnectionService;
import com.homes.zipsai.global.response.ApiResponse;
import com.homes.zipsai.global.security.AuthPrincipal;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "입주·초대", description = "관리자 초대코드 발급과 입주민 세대 연결을 처리한다.")
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

    @Operation(
            summary = "초대코드 발급·재발급",
            description = "관리자가 담당 호실에 초대코드를 발급한다. "
                    + "기존 활성 코드가 있으면 만료 처리 후 새 코드를 반환한다."
    )
    @PostMapping("/managers/me/rooms/{roomId}/invitation-codes")
    public ResponseEntity<ApiResponse<InvitationCodeResponse>> issue(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable Long roomId
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.data(
                        invitationCodeService.issueOrReissue(getAuthenticatedUserId(principal), roomId)));
    }

    @Operation(
            summary = "초대코드 검증",
            description = "입주민이 초대코드를 확인하고 연결될 건물·호실 정보를 조회한다. "
                    + "검증만 수행하며 코드는 사용 처리하지 않는다."
    )
    @GetMapping("/residents/me/invitation-codes/{code}")
    public ResponseEntity<ApiResponse<InvitationCodeValidationResponse>> validate(@PathVariable String code) {
        return ResponseEntity.ok(ApiResponse.data(invitationCodeService.validateInvitationCode(code)));
    }

    @Operation(
            summary = "세대 연결 확정",
            description = "입주민이 유효한 초대코드를 사용해 세대와 연결한다."
    )
    @PutMapping("/residents/me/room")
    public ResponseEntity<ApiResponse<RoomConnectionResponse>> connect(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody RoomConnectionRequest request
    ) {
        return ResponseEntity.ok(ApiResponse.data(
                roomConnectionService.connectRoom(getAuthenticatedUserId(principal), request.code())));
    }

    @Operation(
            summary = "초대코드 취소",
            description = "관리자가 활성 초대코드를 만료 처리하고 호실을 공실로 되돌린다."
    )
    @DeleteMapping("/managers/me/rooms/{roomId}/invitation-codes/{codeId}")
    public ResponseEntity<ApiResponse<Void>> cancel(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
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
