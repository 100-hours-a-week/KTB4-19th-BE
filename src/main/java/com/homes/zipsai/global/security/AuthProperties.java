package com.homes.zipsai.global.security;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.auth")
public record AuthProperties(
        String secret,
        Duration accessTtl,
        Duration refreshTtl,
        boolean secureCookie,
        List<String> allowedOrigins,
        int rateLimit
) {

    public AuthProperties {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("app.auth.secret must contain at least 32 bytes");
        }
        accessTtl = accessTtl == null ? Duration.ofMinutes(15) : accessTtl;
        refreshTtl = refreshTtl == null ? Duration.ofDays(14) : refreshTtl;
        allowedOrigins = allowedOrigins == null
                ? List.of("http://localhost:3000")
                : List.copyOf(allowedOrigins);
        rateLimit = rateLimit <= 0 ? 5 : rateLimit;
        if (accessTtl.isNegative() || accessTtl.isZero()
                || refreshTtl.isNegative() || refreshTtl.isZero()) {
            throw new IllegalArgumentException("Token lifetimes must be positive");
        }
    }
}
