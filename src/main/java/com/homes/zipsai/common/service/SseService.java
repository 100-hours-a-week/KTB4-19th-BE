package com.homes.zipsai.common.service;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

import jakarta.annotation.PreDestroy;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter.DataWithMediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.homes.zipsai.common.config.SseProperties;
import com.homes.zipsai.common.connection.SseConnection;
import com.homes.zipsai.global.exception.ApiException;
import com.homes.zipsai.user.service.UserService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class SseService {

    private final UserService userService;
    private final SseProperties sseProperties;

    private final Map<Long, Set<SseConnection>> connections = new ConcurrentHashMap<>();

    public SseEmitter subscribe(long userId) {
        Instant now = Instant.now();
        userService.requireEligibleUser(userId);
        Instant expiresAt = now.plus(sseProperties.timeout());
        SseConnection connection = new SseConnection(userId, expiresAt,
            new SseEmitter(Math.max(1, Duration.between(now, expiresAt).toMillis())));
        synchronized (connection) {
            connection.getEmitter().onCompletion(() -> remove(connection));
            connection.getEmitter().onTimeout(() -> complete(connection));
            connection.getEmitter().onError(exception -> remove(connection));
            connections.compute(connection.getUserId(), (ignoredUserId, current) -> {
                Set<SseConnection> result = current == null ? new CopyOnWriteArraySet<>() : current;
                result.add(connection);
                return result;
            });
            send(connection, SseEmitter.event().comment("connected").build());
        }
        return connection.getEmitter();
    }

    public void send(long userId, SseEmitter.SseEventBuilder event) {
        Set<SseConnection> current = connections.get(userId);
        if (current == null) {
            return;
        }
        Set<DataWithMediaType> data = event.build();
        for (SseConnection connection : current) {
            try {
                if (isActive(connection)) {
                    send(connection, data);
                }
            } catch (RuntimeException ignored) {
                complete(connection);
            }
        }
    }

    private boolean isActive(SseConnection connection) {
        synchronized (connection) {
            if (connection.isClosed()) {
                return false;
            }
            if (!connection.getExpiresAt().isAfter(Instant.now())) {
                complete(connection);
                return false;
            }
        }
        try {
            userService.requireEligibleUser(connection.getUserId());
            return true;
        } catch (ApiException exception) {
            complete(connection);
            return false;
        }
    }

    @Scheduled(fixedDelayString = "${app.sse.heartbeat:" + SseProperties.DEFAULT_HEARTBEAT + "}")
    public void sendHeartbeat() {
        connections.values().forEach(current -> current.forEach(connection -> {
            try {
                if (isActive(connection)) {
                    send(connection, SseEmitter.event().comment("heartbeat").build());
                }
            } catch (RuntimeException ignored) {
                complete(connection);
            }
        }));
    }

    private void send(SseConnection connection, Set<DataWithMediaType> event) {
        synchronized (connection) {
            if (connection.isClosed()) {
                return;
            }
            if (!connection.getExpiresAt().isAfter(Instant.now())) {
                complete(connection);
                return;
            }
            try {
                connection.getEmitter().send(event);
            } catch (IOException exception) {
                remove(connection);
            } catch (RuntimeException exception) {
                complete(connection);
            }
        }
    }

    private void remove(SseConnection connection) {
        synchronized (connection) {
            connection.markClosed();
            connections.computeIfPresent(connection.getUserId(), (userId, current) -> {
                current.remove(connection);
                return current.isEmpty() ? null : current;
            });
        }
    }

    private void complete(SseConnection connection) {
        remove(connection);
        connection.getEmitter().complete();
    }

    @PreDestroy
    public void close() {
        connections.values().forEach(current -> current.forEach(this::complete));
    }
}
