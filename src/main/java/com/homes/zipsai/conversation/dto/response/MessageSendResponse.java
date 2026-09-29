package com.homes.zipsai.conversation.dto.response;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;

import com.homes.zipsai.conversation.domain.MessageType;
import com.homes.zipsai.conversation.domain.SenderType;

public record MessageSendResponse(
    Long messageId,
    Long conversationId,
    SenderType senderType,
    MessageType messageType,
    String content,
    List<AttachmentResponse> attachments,
    LocalDateTime createdAt,
    MessageResponse assistantMessage,
    OffsetDateTime closesAt
) {

    public static MessageSendResponse of(Long conversationId, MessageResponse message,
                                         MessageResponse assistantMessage, OffsetDateTime closesAt) {
        return new MessageSendResponse(message.messageId(), conversationId, message.senderType(),
            message.messageType(), message.content(), message.attachments(), message.createdAt(),
            assistantMessage, closesAt);
    }
}
