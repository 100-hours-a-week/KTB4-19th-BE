package com.homes.zipsai.conversation.service;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("app.conversation.reply-lock")
public record ConversationReplyLockProperties(@DefaultValue("150s") Duration ttl) {
}
