package com.homes.zipsai.common.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("app.sse")
public record SseProperties(
        @DefaultValue(SseProperties.DEFAULT_HEARTBEAT) Duration heartbeat,
        @DefaultValue("5m") Duration timeout
) {

    public static final String DEFAULT_HEARTBEAT = "30s";

    public SseProperties {
        if (heartbeat.isZero() || heartbeat.isNegative() || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("SSE durations must be positive");
        }
    }
}
