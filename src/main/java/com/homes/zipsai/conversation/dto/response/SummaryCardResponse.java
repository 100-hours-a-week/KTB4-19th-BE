package com.homes.zipsai.conversation.dto.response;

import java.time.OffsetDateTime;

import com.homes.zipsai.building.domain.ComplaintType;
import com.homes.zipsai.conversation.ai.AiComplaintDraft;

public record SummaryCardResponse(
    ComplaintType complaintType,
    String location,
    OffsetDateTime occurredTime,
    String symptom,
    long attachmentCount
) {

    public static SummaryCardResponse of(ComplaintType complaintType, AiComplaintDraft draft, long attachmentCount) {
        return new SummaryCardResponse(complaintType, draft.location(), draft.occurredAt(), draft.symptom(),
            attachmentCount);
    }
}
