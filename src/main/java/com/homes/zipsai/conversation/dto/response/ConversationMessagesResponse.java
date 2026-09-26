package com.homes.zipsai.conversation.dto.response;

import java.util.List;

import com.homes.zipsai.conversation.domain.Conversation;
import com.homes.zipsai.conversation.domain.ConversationStatus;

public record ConversationMessagesResponse(
    Long conversationId,
    String conversationTitle,
    ConversationStatus conversationStatus,
    String statusLabel,
    Long complaintId,
    boolean hasNext,
    Long nextCursor,
    List<MessageResponse> messages
) {

    public static ConversationMessagesResponse of(Conversation conversation, Long complaintId,
                                                  List<MessageResponse> messages, boolean hasNext, Long nextCursor) {
        ConversationStatus status = conversation.getStatus();
        return new ConversationMessagesResponse(conversation.getId(), conversation.getTitle(), status,
            status.getLabel(), complaintId, hasNext, nextCursor, messages);
    }
}
