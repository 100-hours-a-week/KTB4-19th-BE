package com.homes.zipsai.conversation.ai;

import java.time.OffsetDateTime;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AiComplaintFeedbackRequest(
        long complaintDetailId,
        long buildingId,
        String complaintType,
        String issueType,
        String location,
        String symptom,
        OffsetDateTime occurredTime,
        String comment,
        OffsetDateTime updatedAt
) {
}
