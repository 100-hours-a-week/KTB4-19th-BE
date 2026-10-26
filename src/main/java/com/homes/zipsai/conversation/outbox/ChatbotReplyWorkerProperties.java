package com.homes.zipsai.conversation.outbox;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("app.conversation.reply-worker")
public record ChatbotReplyWorkerProperties(
        @DefaultValue("130s") Duration attemptTimeout,
        @DefaultValue("280s") Duration requestTimeout
) {
}
