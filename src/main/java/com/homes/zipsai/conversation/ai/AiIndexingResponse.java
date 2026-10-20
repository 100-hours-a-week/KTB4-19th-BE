package com.homes.zipsai.conversation.ai;

import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AiIndexingResponse(
        String traceId,
        List<Result> results
) {

    public static final String KIND_INDEX = "document_index";
    public static final String KIND_CLEANUP = "document_cleanup";
    public static final String STATUS_SUCCEEDED = "succeeded";
    public static final String STATUS_FAILED = "failed";
    public static final String STATUS_SKIPPED = "skipped";

    public AiIndexingResponse {
        results = results == null ? List.of() : List.copyOf(results);
    }

    public static AiIndexingResponse empty() {
        return new AiIndexingResponse(null, List.of());
    }

    public Optional<Result> indexResult(String docId) {
        return results.stream()
                .filter(result -> KIND_INDEX.equals(result.kind()) && docId.equals(result.docId()))
                .findFirst();
    }

    public Optional<Result> cleanupResult() {
        return results.stream().filter(result -> KIND_CLEANUP.equals(result.kind())).findFirst();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Result(
            String kind,
            String docId,
            String status,
            String reasonCode
    ) {
    }
}
