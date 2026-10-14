package com.homes.zipsai.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import com.homes.zipsai.global.exception.InvalidPubSubFieldException;
import com.homes.zipsai.global.exception.InvalidPubSubFieldException.Reason;

@ConfigurationProperties("app.redis.pubsub")
public record RedisPubSubProperties(@DefaultValue("notification-events") String notificationChannel) {

    public RedisPubSubProperties {
        if (notificationChannel == null || notificationChannel.isBlank()) {
            throw new InvalidPubSubFieldException("notificationChannel", Reason.REQUIRED_VALUE);
        }
    }
}
