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

    public static MessageResponse of(Message message, List<AttachmentResponse> attachments) {
        return of(message, attachments, null);
    }

    public static MessageResponse of(Message message, List<AttachmentResponse> attachments,
                                     SummaryCardResponse summaryCard) {
        SummaryCardResponse shownSummaryCard = message.getMessageType() == MessageType.SUMMARY_CARD
            ? summaryCard
            : null;
        return new MessageResponse(message.getId(), message.getSenderType(), message.getMessageType(),
            message.getContent(), attachments, shownSummaryCard, message.getCreatedAt());
    }
}
