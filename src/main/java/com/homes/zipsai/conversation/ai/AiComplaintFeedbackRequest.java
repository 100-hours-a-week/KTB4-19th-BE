package com.homes.zipsai.conversation.ai;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AiComplaintFeedbackRequest(
        long buildingId,
        String complaintType,
        String issueType,
        String location,
        String symptom,
        OffsetDateTime occurredTime,
        String comment,
        LocalDateTime updatedAt
) {
}
