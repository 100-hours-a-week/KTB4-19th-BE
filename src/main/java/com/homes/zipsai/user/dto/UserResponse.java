package com.homes.zipsai.user.dto;

import com.homes.zipsai.user.domain.UserRole;

public record UserResponse(Long userId, String email, UserRole userRole, String userName, String phone) {
}
