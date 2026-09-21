package com.homes.zipsai.conversation.dto.response;

import java.time.LocalDateTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.homes.zipsai.conversation.domain.Message;
import com.homes.zipsai.conversation.domain.MessageType;
import com.homes.zipsai.conversation.domain.SenderType;

public record MessageResponse(
    Long messageId,
    SenderType senderType,
    MessageType messageType,
    String content,
    List<AttachmentResponse> attachments,
    @JsonInclude(JsonInclude.Include.NON_NULL)
    SummaryCardResponse summaryCard,
    LocalDateTime createdAt
) {

    public static MessageResponse from(Message message) {
        SummaryCardResponse summaryCard = message.getMessageType() == MessageType.SUMMARY_CARD
            ? SummaryCardResponse.from(message.getConversation().currentDraft())
            : null;
        return new MessageResponse(message.getId(), message.getSenderType(), message.getMessageType(),
            message.getContent(), List.of(), summaryCard, message.getCreatedAt());
    }
}
