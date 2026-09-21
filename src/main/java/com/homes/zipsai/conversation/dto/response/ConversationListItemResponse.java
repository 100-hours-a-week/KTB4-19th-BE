package com.homes.zipsai.conversation.dto.response;

import java.time.LocalDateTime;

import com.homes.zipsai.building.domain.Complaint;
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

    public static ConversationListItemResponse of(Conversation conversation, Complaint complaint) {
        ConversationDisplayStatus status = ConversationDisplayStatus.of(conversation, complaint);
        return new ConversationListItemResponse(conversation.getId(), conversation.getTitle(), conversation.getType(),
            status.code(), status.label(), conversation.getLastMessageAt());
    }
}
