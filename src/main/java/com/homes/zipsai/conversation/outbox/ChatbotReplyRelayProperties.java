package com.homes.zipsai.conversation.outbox;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("app.conversation.reply-relay")
public record ChatbotReplyRelayProperties(
        @DefaultValue("chatbot.reply") String exchange,
        @DefaultValue("chatbot.reply.request") String routingKey,
        @DefaultValue("10s") Duration republishDelay,
        @DefaultValue("5s") Duration confirmTimeout,
        @DefaultValue("50") int batchSize
) {
}
