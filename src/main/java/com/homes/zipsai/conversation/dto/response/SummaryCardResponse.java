package com.homes.zipsai.conversation.dto.response;

import java.time.OffsetDateTime;

import com.homes.zipsai.conversation.ai.AiComplaintDraft;

public record SummaryCardResponse(
    String location,
    OffsetDateTime occurredTime,
    String symptom,
    long attachmentCount
) {

    public static SummaryCardResponse of(AiComplaintDraft draft, long attachmentCount) {
        return new SummaryCardResponse(draft.location(), draft.occurredAt(), draft.symptom(), attachmentCount);
    }
}
