package com.homes.zipsai.migration.domain;

import java.time.OffsetDateTime;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record MigrationSettings(
    MigrationMode mode,
    OffsetDateTime switchAt,
    WorkerMode workerMode,
    Boolean forwardWorkerEnabled,
    Boolean reverseWorkerEnabled,
    Integer v2ReadPercent,
    Integer shadowReadPercent
) {
    public static final MigrationSettings DEFAULT = new MigrationSettings(MigrationMode.V1_PRIMARY, null,
        WorkerMode.PAUSED, false, false, 0, 0);

    private static final int MAX_PERCENT = 100;

    public MigrationSettings {
        forwardWorkerEnabled = Boolean.TRUE.equals(forwardWorkerEnabled);
        reverseWorkerEnabled = Boolean.TRUE.equals(reverseWorkerEnabled);
        v2ReadPercent = v2ReadPercent == null ? 0 : v2ReadPercent;
        shadowReadPercent = shadowReadPercent == null ? 0 : shadowReadPercent;
        if (mode == null || workerMode == null) {
            throw new IllegalArgumentException("mode와 workerMode는 필수입니다.");
        }
        verifyPercent("v2ReadPercent", v2ReadPercent);
        verifyPercent("shadowReadPercent", shadowReadPercent);
    }

    public MigrationMode modeAt(OffsetDateTime now) {
        if (switchAt == null || !now.isBefore(switchAt)) {
            return mode;
        }
        return mode.opposite();
    }

    public int v2ReadPercentAt(OffsetDateTime now) {
        return modeAt(now) == MigrationMode.V2_PRIMARY ? MAX_PERCENT : v2ReadPercent;
    }

    public boolean isWorkerRunning() {
        return workerMode == WorkerMode.RUNNING;
    }

    public boolean isWorkerEnabled(OutboxDirection direction) {
        return direction == OutboxDirection.V1_TO_V2 ? forwardWorkerEnabled : reverseWorkerEnabled;
    }

    private static void verifyPercent(String name, int percent) {
        if (percent < 0 || percent > MAX_PERCENT) {
            throw new IllegalArgumentException(name + "는 0에서 100 사이여야 합니다.");
        }
    }
}
