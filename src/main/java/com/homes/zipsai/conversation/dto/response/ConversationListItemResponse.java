package com.homes.zipsai.conversation.dto.response;

import java.time.LocalDateTime;

import com.homes.zipsai.conversation.domain.Conversation;
import com.homes.zipsai.conversation.domain.ConversationType;

public record ConversationListItemResponse(
    Long conversationId,
    String conversationTitle,
    ConversationType conversationType,
    String statusCode,
    String statusLabel,
    LocalDateTime lastMessageAt
) {

    public static ConversationListItemResponse from(Conversation conversation) {
        return new ConversationListItemResponse(conversation.getId(), conversation.getTitle(), conversation.getType(),
            conversation.getStatus().name(), conversation.getStatus().getLabel(), conversation.getLastMessageAt());
    }
}
