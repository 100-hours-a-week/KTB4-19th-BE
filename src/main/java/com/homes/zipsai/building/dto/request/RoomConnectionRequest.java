package com.homes.zipsai.building.dto.request;

import jakarta.validation.constraints.NotBlank;

public record RoomConnectionRequest(@NotBlank String code) {
}
