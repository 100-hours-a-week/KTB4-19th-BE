package com.homes.zipsai.conversation.batch;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("app.conversation.reply-outbox-cleanup")
public record ChatbotReplyOutboxCleanupProperties(
        @DefaultValue("7d") Duration completedRetention,
        @DefaultValue("30d") Duration failedRetention,
        @DefaultValue("1000") int batchSize
) {
}
