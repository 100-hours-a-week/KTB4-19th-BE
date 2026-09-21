package com.homes.zipsai.conversation.service;

import java.time.LocalDateTime;

import com.homes.zipsai.conversation.ai.AiConverseRequest;
import com.homes.zipsai.conversation.domain.Conversation;
import com.homes.zipsai.conversation.dto.response.MessageResponse;

public record PendingAiReply(
    Conversation conversation,
    MessageResponse residentMessage,
    boolean newConversation,
    LocalDateTime previousLastMessageAt,
    AiConverseRequest aiRequest
) {
}
