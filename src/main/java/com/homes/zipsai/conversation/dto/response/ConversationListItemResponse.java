package com.homes.zipsai.conversation.dto.response;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;

import com.homes.zipsai.conversation.domain.Conversation;
import com.homes.zipsai.conversation.domain.ConversationStatus;
import com.homes.zipsai.conversation.domain.ConversationType;

public record ConversationListItemResponse(
    Long conversationId,
    String conversationTitle,
    ConversationType conversationType,
    String statusCode,
    String statusLabel,
    LocalDateTime lastMessageAt,
    OffsetDateTime closesAt
) {

    public static ConversationListItemResponse of(Conversation conversation, LocalDateTime now) {
        ConversationStatus status = conversation.statusAt(now);
        return new ConversationListItemResponse(conversation.getId(), conversation.getTitle(), conversation.getType(),
            status.name(), status.getLabel(), conversation.getLastMessageAt(), conversation.closesAt());
    }
}
