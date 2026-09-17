package com.homes.zipsai.conversation.dto.response;

import java.time.LocalDateTime;

import com.homes.zipsai.conversation.domain.Conversation;
import com.homes.zipsai.conversation.domain.ConversationStatus;

public record ConversationStatusResponse(
    Long conversationId,
    ConversationStatus conversationStatus,
    String conversationStatusLabel,
    LocalDateTime updatedAt
) {

    public static ConversationStatusResponse from(Conversation conversation) {
        return new ConversationStatusResponse(conversation.getId(), conversation.getStatus(),
            conversation.getStatus().getLabel(), conversation.getUpdatedAt());
    }
}
