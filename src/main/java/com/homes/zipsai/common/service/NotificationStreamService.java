package com.homes.zipsai.common.service;

import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class NotificationStreamService {

    private final SseService sseService;

    public void sendNotification(long userId, long userNotiId) {
        sseService.send(userId, SseEmitter.event()
            .id(Long.toString(userNotiId)).name("notification")
            .data(Map.of("message", "success", "data", Map.of("userNotiId", userNotiId)), MediaType.APPLICATION_JSON));
    }
}
