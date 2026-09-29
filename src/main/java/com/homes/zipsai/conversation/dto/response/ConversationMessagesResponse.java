package com.homes.zipsai.conversation.dto.response;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;

import com.homes.zipsai.conversation.domain.Conversation;
import com.homes.zipsai.conversation.domain.ConversationStatus;

public record ConversationMessagesResponse(
    Long conversationId,
    String conversationTitle,
    ConversationStatus conversationStatus,
    String statusLabel,
    OffsetDateTime closesAt,
    Long complaintId,
    boolean hasNext,
    Long nextCursor,
    List<MessageResponse> messages
) {

    public static ConversationMessagesResponse of(Conversation conversation, Long complaintId,
                                                  List<MessageResponse> messages, boolean hasNext, Long nextCursor,
                                                  LocalDateTime now) {
        ConversationStatus status = conversation.statusAt(now);
        return new ConversationMessagesResponse(conversation.getId(), conversation.getTitle(), status,
            status.getLabel(), conversation.closesAt(), complaintId, hasNext, nextCursor, messages);
    }
}
