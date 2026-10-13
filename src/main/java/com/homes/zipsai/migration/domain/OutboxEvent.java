package com.homes.zipsai.migration.domain;

import java.time.LocalDateTime;

public record OutboxEvent(
    Long eventId,
    OutboxDirection direction,
    String entityType,
    String entityId,
    OutboxStatus status,
    int retryCount,
    LocalDateTime nextRetryAt,
    LocalDateTime createdAt
) {
    public boolean isFailed() {
        return status == OutboxStatus.FAILED;
    }

    public boolean isWaitingForRetry(LocalDateTime now) {
        return nextRetryAt != null && nextRetryAt.isAfter(now);
    }
}
