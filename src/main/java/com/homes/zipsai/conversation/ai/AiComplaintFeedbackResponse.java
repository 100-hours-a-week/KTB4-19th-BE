package com.homes.zipsai.conversation.ai;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AiComplaintFeedbackResponse(
        String traceId,
        List<Result> results
) {

    public static final String STATUS_SUCCEEDED = "succeeded";
    public static final String STATUS_FAILED = "failed";
    public static final String STATUS_SKIPPED = "skipped";

    public AiComplaintFeedbackResponse {
        results = results == null ? List.of() : List.copyOf(results);
    }

    public static AiComplaintFeedbackResponse empty() {
        return new AiComplaintFeedbackResponse(null, List.of());
    }

    public Optional<Result> find(long complaintDetailId) {
        return results.stream()
                .filter(result -> result.complaintDetailId() != null
                        && result.complaintDetailId() == complaintDetailId)
                .findFirst();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Result(
            Long complaintDetailId,
            OffsetDateTime updatedAt,
            String status,
            String reasonCode,
            Boolean retryable
    ) {

        public Result(Long complaintDetailId, OffsetDateTime updatedAt, String status, String reasonCode) {
            this(complaintDetailId, updatedAt, status, reasonCode, null);
        }
    }
}
