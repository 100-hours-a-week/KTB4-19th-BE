package com.homes.zipsai.conversation.dto.response;

public record ConversationReplyDeltaResponse(
    Long conversationId,
    int seq,
    String text
) {
}
