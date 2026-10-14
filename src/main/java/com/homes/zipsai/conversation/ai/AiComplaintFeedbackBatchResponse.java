package com.homes.zipsai.conversation.ai;

import java.util.List;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AiComplaintFeedbackBatchResponse(List<Result> results) {

    public AiComplaintFeedbackBatchResponse {
        results = results == null ? List.of() : List.copyOf(results);
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Result(long complaintDetailId, String status, String reason) {

        public boolean delivered() {
            return "ACCEPTED".equals(status) || "DUPLICATE".equals(status);
        }

        public boolean rejected() {
            return "REJECTED".equals(status);
        }
    }
}
