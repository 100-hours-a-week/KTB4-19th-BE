package com.homes.zipsai.conversation.ai;

import java.time.OffsetDateTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AiProcessingNotification(
        String kind,
        String traceId,
        List<Result> results
) {

    public static final String KIND_COMMENT_UPSERT = "comment_upsert";
    public static final String KIND_COMMENT_DELETE = "comment_delete";
    public static final String STATUS_SUCCEEDED = "succeeded";
    public static final String STATUS_FAILED = "failed";
    public static final String STATUS_SKIPPED = "skipped";

    public AiProcessingNotification {
        results = results == null ? List.of() : List.copyOf(results);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Result(
            Long complaintDetailId,
            OffsetDateTime updatedAt,
            String status,
            String reasonCode
    ) {
    }
}
