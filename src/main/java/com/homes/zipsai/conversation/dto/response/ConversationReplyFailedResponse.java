package com.homes.zipsai.conversation.dto.response;

import java.util.Map;

import com.homes.zipsai.global.exception.ApiException;

public record ConversationReplyFailedResponse(
    Long conversationId,
    String code,
    String message,
    Map<String, ?> details
) {

    public static ConversationReplyFailedResponse of(Long conversationId, ApiException exception) {
        return new ConversationReplyFailedResponse(conversationId, exception.code, exception.getMessage(),
            exception.details);
    }
}
