package com.homes.zipsai.building.domain;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "Building_coordinate_retry_jobs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BuildingCoordinateRetryJob {

    @Id
    @Column(name = "building_id")
    private Long id;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "building_id", nullable = false, updatable = false)
    private Building building;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private BuildingCoordinateRetryJobStatus status = BuildingCoordinateRetryJobStatus.PENDING;

    @Column(name = "retry_failure_count", nullable = false)
    private short retryFailureCount;

    @Column(name = "claim_version", nullable = false)
    private long claimVersion;

    @Column(name = "next_retry_at")
    private LocalDateTime nextRetryAt;

    @Column(name = "lease_expires_at")
    private LocalDateTime leaseExpiresAt;

    @Column(name = "last_error_type", nullable = false, length = 30)
    private String lastErrorType;

    @Column(name = "failure_reason", length = 30)
    private String failureReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public BuildingCoordinateRetryJob(Building building, String lastErrorType, LocalDateTime nextRetryAt) {
        this.building = Objects.requireNonNull(building, "building");
        this.nextRetryAt = Objects.requireNonNull(nextRetryAt, "nextRetryAt");
        if (lastErrorType == null || lastErrorType.isBlank() || lastErrorType.length() > 30) {
            throw new IllegalArgumentException("lastErrorType must contain 1 to 30 characters");
        }
        this.lastErrorType = lastErrorType;
    }

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
    }
}
