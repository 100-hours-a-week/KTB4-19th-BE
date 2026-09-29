package com.homes.zipsai.global.security;

public record AuthPrincipal(Long userId) {
    public AuthPrincipal(Long userId, String ignoredSessionId) {
        this(userId);
    }
}
