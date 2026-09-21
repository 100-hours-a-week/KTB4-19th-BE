package com.homes.zipsai.auth.dto;
import com.homes.zipsai.user.dto.UserResponse;

public record LoginResponse(String accessToken, String tokenType, UserResponse user) {}
