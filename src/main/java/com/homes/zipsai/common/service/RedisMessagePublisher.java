package com.homes.zipsai.common.service;

import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.homes.zipsai.common.event.RedisMessage;
import com.homes.zipsai.global.logging.StructuredLogger;

import lombok.RequiredArgsConstructor;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class RedisMessagePublisher {

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final StructuredLogger structuredLogger;

    public void publish(String channel, Object payload) {
        String traceId = MDC.get("traceId");
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString();
        }
        try {
            stringRedisTemplate.convertAndSend(channel, objectMapper.writeValueAsString(new RedisMessage<>(traceId,
                payload)));
            structuredLogger.pubSubDone("publish", channel, traceId);
        } catch (JacksonException | DataAccessException exception) {
            structuredLogger.pubSubFailed("publish", channel, traceId, exception);
        }
    }
}
