package com.homes.zipsai.building.domain;

import java.time.Duration;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.homes.zipsai.global.domain.BaseTimeEntity;
import com.homes.zipsai.global.util.TextUtils;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "Rule_document_indexing_outbox")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RuleDocumentIndexingOutbox extends BaseTimeEntity {

    private static final int LAST_ERROR_MAX_LENGTH = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "outbox_id")
    private Long id;

    @Column(name = "building_id", nullable = false)
    private Long buildingId;

    @Column(name = "document_id")
    private Long documentId;

    @Column(name = "document_version")
    private Integer documentVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "operation", nullable = false, length = 10)
    private RuleDocumentIndexingOperation operation;

    @Column(name = "trace_id", length = 64)
    private String traceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private RuleDocumentIndexingOutboxStatus status;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(name = "next_retry_at", nullable = false)
    private LocalDateTime nextRetryAt;

    @Column(name = "last_error", length = LAST_ERROR_MAX_LENGTH)
    private String lastError;

    @Column(name = "processed_at")
    private LocalDateTime processedAt;

    private RuleDocumentIndexingOutbox(Long buildingId, Long documentId, Integer documentVersion,
                                       RuleDocumentIndexingOperation operation, String traceId, LocalDateTime now) {
        this.buildingId = buildingId;
        this.documentId = documentId;
        this.documentVersion = documentVersion;
        this.operation = operation;
        this.traceId = TextUtils.truncate(traceId, 64);
        this.status = RuleDocumentIndexingOutboxStatus.PENDING;
        this.nextRetryAt = now;
    }

    public static RuleDocumentIndexingOutbox index(RuleDocument document, String traceId, LocalDateTime now) {
        return new RuleDocumentIndexingOutbox(document.getBuilding().getId(), document.getId(), document.getVersion(),
                RuleDocumentIndexingOperation.INDEX, traceId, now);
    }

    public static RuleDocumentIndexingOutbox cleanup(Long buildingId, String traceId, LocalDateTime now) {
        return new RuleDocumentIndexingOutbox(buildingId, null, null, RuleDocumentIndexingOperation.CLEANUP,
                traceId, now);
    }

    public void markDone(LocalDateTime now) {
        status = RuleDocumentIndexingOutboxStatus.DONE;
        processedAt = now;
    }

    public void markFailed(String error, LocalDateTime now) {
        status = RuleDocumentIndexingOutboxStatus.FAILED;
        lastError = TextUtils.truncate(error, LAST_ERROR_MAX_LENGTH);
        processedAt = now;
    }

    public boolean scheduleRetry(String error, int maxRetryCount, Duration baseDelay, Duration maxDelay,
                                 LocalDateTime now) {
        return scheduleRetry(error, maxRetryCount, baseDelay, maxDelay, null, now);
    }

    public boolean scheduleRetry(String error, int maxRetryCount, Duration baseDelay, Duration maxDelay,
                                 Duration minDelay, LocalDateTime now) {
        if (retryCount >= maxRetryCount) {
            markFailed(error, now);
            return false;
        }
        retryCount++;
        lastError = TextUtils.truncate(error, LAST_ERROR_MAX_LENGTH);
        Duration delay = baseDelay.multipliedBy(1L << (retryCount - 1));
        if (minDelay != null && minDelay.compareTo(delay) > 0) {
            delay = minDelay;
        }
        nextRetryAt = now.plus(delay.compareTo(maxDelay) > 0 ? maxDelay : delay);
        return true;
    }
}
