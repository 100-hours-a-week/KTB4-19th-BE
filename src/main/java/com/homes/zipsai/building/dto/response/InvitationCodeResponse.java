package com.homes.zipsai.building.dto.response;

import java.time.LocalDateTime;

import com.homes.zipsai.building.domain.RoomStatus;

public record InvitationCodeResponse(
        Long roomId,
        String roomNo,
        RoomStatus roomStatus,
        String roomStatusLabel,
        Long codeId,
        String code,
        LocalDateTime createdAt,
        LocalDateTime expiresAt,
        boolean reissued
) {
}
