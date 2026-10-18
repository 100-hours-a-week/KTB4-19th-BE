package com.homes.zipsai.conversation.ai;

import java.time.OffsetDateTime;
import java.util.List;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AiComplaintFeedbackDeleteRequest(
        String traceId,
        List<Item> items
) {

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Item(long buildingId, long complaintDetailId, OffsetDateTime updatedAt) {
    }
}
