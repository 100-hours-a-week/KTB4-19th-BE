package com.homes.zipsai.conversation.dto.response;

import java.time.OffsetDateTime;

import com.homes.zipsai.conversation.ai.AiComplaintDraft;

public record SummaryCardResponse(
    String location,
    OffsetDateTime occurredTime,
    String symptom,
    int attachmentCount
) {

    private static final int ATTACHMENT_COUNT_NOT_SUPPORTED = 0;

    public static SummaryCardResponse from(AiComplaintDraft draft) {
        return new SummaryCardResponse(draft.location(), draft.occurredAt(), draft.symptom(),
            ATTACHMENT_COUNT_NOT_SUPPORTED);
    }
}
