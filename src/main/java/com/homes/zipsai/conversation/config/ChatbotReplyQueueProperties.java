package com.homes.zipsai.conversation.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("app.conversation.reply-queue")
public record ChatbotReplyQueueProperties(
        @DefaultValue("chatbot.reply.request") String queue,
        @DefaultValue(DEFAULT_DEAD_LETTER_QUEUE) String deadLetterQueue,
        @DefaultValue("10s") Duration delayedRetryMin,
        @DefaultValue("30s") Duration delayedRetryMax
) {

    public static final String DEFAULT_DEAD_LETTER_QUEUE = "chatbot.reply.request.dlq";
}
