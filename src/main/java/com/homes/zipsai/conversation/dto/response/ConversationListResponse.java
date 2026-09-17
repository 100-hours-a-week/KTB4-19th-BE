package com.homes.zipsai.conversation.dto.response;

import java.util.List;

public record ConversationListResponse(
    boolean hasNext,
    String nextCursor,
    List<ConversationListItemResponse> conversations
) {
}
