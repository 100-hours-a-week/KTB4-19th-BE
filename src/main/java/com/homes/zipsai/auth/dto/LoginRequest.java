package com.homes.zipsai.auth.dto;

public record LoginRequest(
    String email,
    String password
) {
}
