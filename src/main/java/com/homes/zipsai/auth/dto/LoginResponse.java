package com.homes.zipsai.auth.dto;
import java.util.Map;
public record LoginResponse(String accessToken, String tokenType, Map<String, Object> user) {}
