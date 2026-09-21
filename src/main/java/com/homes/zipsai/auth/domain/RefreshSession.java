package com.homes.zipsai.auth.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "Refresh_sessions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefreshSession {
    @Id
    @Column(name = "session_id", length = 36)
    private String id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "token_hash", nullable = false, length = 64, unique = true)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(nullable = false)
    private boolean revoked;

    public RefreshSession(String id, Long userId, String hash, Instant expiresAt) {
        this.id = id;
        this.userId = userId;
        tokenHash = hash;
        this.expiresAt = expiresAt;
    }

    public boolean active() {
        return !revoked && expiresAt.isAfter(Instant.now());
    }

    public void rotate(String hash) {
        tokenHash = hash;
    }

    public void revoke() {
        revoked = true;
    }
}
