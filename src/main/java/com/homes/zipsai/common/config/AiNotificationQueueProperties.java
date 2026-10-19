package com.homes.zipsai.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.ai.notification-queue")
public record AiNotificationQueueProperties(
        boolean enabled,
        String queueUrl,
        String awsRegion,
        String awsProfile,
        int waitTimeSeconds,
        int maxMessages
) {
    public AiNotificationQueueProperties {
        awsRegion = awsRegion == null || awsRegion.isBlank() ? "ap-northeast-2" : awsRegion;
        waitTimeSeconds = waitTimeSeconds <= 0 || waitTimeSeconds > 20 ? 20 : waitTimeSeconds;
        maxMessages = maxMessages <= 0 || maxMessages > 10 ? 10 : maxMessages;
    }
}
