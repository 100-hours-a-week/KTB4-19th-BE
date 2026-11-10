package com.homes.zipsai.conversation.service;

import com.homes.zipsai.conversation.ai.AiConverseRequest;
import com.homes.zipsai.conversation.domain.ChatbotReplyOutbox;
import com.homes.zipsai.conversation.domain.Conversation;
import com.homes.zipsai.conversation.dto.response.MessageResponse;

public record PendingAiReply(
    Long eventId,
    int attempt,
    String lockToken,
    Long userId,
    Conversation conversation,
    MessageResponse residentMessage,
    AiConverseRequest aiRequest
) {

    public static PendingAiReply of(ChatbotReplyOutbox outbox, String lockToken, Conversation conversation,
                                    MessageResponse residentMessage, AiConverseRequest aiRequest) {
        return new PendingAiReply(outbox.getId(), outbox.getAttemptCount(), lockToken, outbox.getUserId(),
            conversation, residentMessage, aiRequest);
    }
}
