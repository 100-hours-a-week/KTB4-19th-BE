package com.homes.zipsai.building.dto;

import jakarta.validation.constraints.NotBlank;

public record RoomConnectionRequest(@NotBlank String code) {
}
