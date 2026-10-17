package com.homes.zipsai.conversation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("app.redis.pubsub")
public record ChatbotReplyPubSubProperties(@DefaultValue("chatbot-reply-events") String chatbotReplyChannel) {
}
