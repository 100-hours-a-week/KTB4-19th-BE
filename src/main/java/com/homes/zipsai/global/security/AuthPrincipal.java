package com.homes.zipsai.global.security;

/** The authenticated user identity carried into application services. */
public record AuthPrincipal(Long userId) {
    /**
     * Backward-compatible constructor for callers that still pass the removed session id.
     * Session validation is no longer part of access-token authentication.
     */
    public AuthPrincipal(Long userId, String ignoredSessionId) {
        this(userId);
    }
}
