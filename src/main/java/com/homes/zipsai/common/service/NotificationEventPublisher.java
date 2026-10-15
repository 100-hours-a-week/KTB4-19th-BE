package com.homes.zipsai.common.service;

import org.springframework.stereotype.Service;

import com.homes.zipsai.common.config.RedisPubSubProperties;
import com.homes.zipsai.common.event.NotificationEvent;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class NotificationEventPublisher {

    private final RedisMessagePublisher redisMessagePublisher;
    private final RedisPubSubProperties redisPubSubProperties;

    public void publish(NotificationEvent notificationEvent) {
        redisMessagePublisher.publish(redisPubSubProperties.notificationChannel(), notificationEvent);
    }
}
