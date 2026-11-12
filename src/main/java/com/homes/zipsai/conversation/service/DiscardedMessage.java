package com.homes.zipsai.conversation.service;

public record DiscardedMessage(
    Long userId,
    Long conversationId
) {
}
