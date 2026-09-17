package com.homes.zipsai.conversation.dto.response;

import com.homes.zipsai.conversation.domain.Conversation;
import com.homes.zipsai.conversation.domain.ConversationStatus;
import com.homes.zipsai.conversation.domain.ConversationType;

public record ConversationCreateResponse(
    Long conversationId,
    ConversationType conversationType,
    ConversationStatus conversationStatus,
    String conversationTitle,
    MessageResponse message,
    MessageResponse assistantMessage
) {

    public static ConversationCreateResponse of(Conversation conversation, MessageResponse message,
                                                MessageResponse assistantMessage) {
        return new ConversationCreateResponse(conversation.getId(), conversation.getType(), conversation.getStatus(),
            conversation.getTitle(), message, assistantMessage);
    }
}
