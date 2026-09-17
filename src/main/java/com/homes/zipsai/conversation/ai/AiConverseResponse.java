package com.homes.zipsai.conversation.ai;

public record AiConverseResponse(
    AiRoute route,
    AiConversationState nextState,
    String reply,
    AiComplaintDraft complaintDraft
) {
}
