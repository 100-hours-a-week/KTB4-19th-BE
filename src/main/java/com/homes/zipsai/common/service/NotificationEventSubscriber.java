package com.homes.zipsai.common.service;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import com.homes.zipsai.common.config.RedisPubSubProperties;
import com.homes.zipsai.common.event.NotificationEvent;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class NotificationEventSubscriber implements MessageListener {

    private final RedisMessageSubscriber redisMessageSubscriber;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final RedisPubSubProperties redisPubSubProperties;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        redisMessageSubscriber.receive(message, redisPubSubProperties.notificationChannel(), NotificationEvent.class,
            applicationEventPublisher::publishEvent);
    }
}
