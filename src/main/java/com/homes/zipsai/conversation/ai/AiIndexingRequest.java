package com.homes.zipsai.conversation.ai;

import java.util.List;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AiIndexingRequest(
        long buildingId,
        String docId,
        String title,
        String fileKey,
        List<String> validDocIds
) {
}
