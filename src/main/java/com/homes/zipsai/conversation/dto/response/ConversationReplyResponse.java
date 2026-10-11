package com.homes.zipsai.conversation.dto.response;

import java.time.OffsetDateTime;

public record ConversationReplyResponse(
    Long conversationId,
    MessageResponse assistantMessage,
    OffsetDateTime closesAt
) {
}
