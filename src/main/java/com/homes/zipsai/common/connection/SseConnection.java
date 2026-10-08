package com.homes.zipsai.common.connection;

import java.time.Instant;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public final class SseConnection {
    private final long userId;
    private final Instant expiresAt;
    private final SseEmitter emitter;
    private boolean closed;

    public void markClosed() {
        this.closed = true;
    }
}
