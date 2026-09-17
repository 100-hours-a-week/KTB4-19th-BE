package com.homes.zipsai.conversation.dto.response;

import java.util.List;

import com.homes.zipsai.building.domain.Complaint;
import com.homes.zipsai.conversation.domain.Conversation;
import com.homes.zipsai.conversation.domain.ConversationStatus;
import com.homes.zipsai.conversation.domain.ConversationType;

public record ConversationMessagesResponse(
    Long conversationId,
    String conversationTitle,
    ConversationType conversationType,
    ConversationStatus conversationStatus,
    String statusCode,
    String statusLabel,
    Long complaintId,
    boolean hasNext,
    Long nextCursor,
    List<MessageResponse> messages
) {

    public static ConversationMessagesResponse of(Conversation conversation, Complaint complaint,
                                                  List<MessageResponse> messages, boolean hasNext, Long nextCursor) {
        ConversationDisplayStatus status = ConversationDisplayStatus.of(conversation, complaint);
        Long complaintId = complaint != null ? complaint.getId() : null;
        return new ConversationMessagesResponse(conversation.getId(), conversation.getTitle(), conversation.getType(),
            conversation.getStatus(), status.code(), status.label(), complaintId, hasNext, nextCursor, messages);
    }
}
