package com.homes.zipsai.auth.dto.response;
import com.homes.zipsai.user.dto.response.UserResponse;

public record LoginResponse(String accessToken, String tokenType, UserResponse user) {}
