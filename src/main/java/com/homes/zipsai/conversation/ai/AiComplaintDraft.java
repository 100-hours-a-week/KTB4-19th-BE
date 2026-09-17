package com.homes.zipsai.conversation.ai;

import java.time.OffsetDateTime;
import java.time.ZoneId;

public record AiComplaintDraft(String location, String symptom, OffsetDateTime occurredAt) {

    public static final AiComplaintDraft EMPTY = new AiComplaintDraft(null, null, null);

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    public AiComplaintDraft {
        occurredAt = occurredAt == null ? null : occurredAt.atZoneSameInstant(KST).toOffsetDateTime();
    }

    public AiComplaintDraft withEdits(AiComplaintDraft edits) {
        return new AiComplaintDraft(
            edits.location() != null ? edits.location() : location,
            edits.symptom() != null ? edits.symptom() : symptom,
            edits.occurredAt() != null ? edits.occurredAt() : occurredAt);
    }

    public boolean isEmpty() {
        return location == null && symptom == null && occurredAt == null;
    }
}
