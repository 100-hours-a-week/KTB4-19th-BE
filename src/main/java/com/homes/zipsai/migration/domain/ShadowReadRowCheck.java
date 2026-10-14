package com.homes.zipsai.migration.domain;

import java.time.Instant;
import java.util.List;

import com.homes.zipsai.migration.domain.ShadowReadComparison.FieldDifference;

public record ShadowReadRowCheck(
    String tableName,
    Instant readStartedAt,
    Instant readFinishedAt,
    List<String> resolvedIds,
    List<String> laggingIds,
    List<String> persistentIds,
    List<FieldDifference> columnDifferences,
    int columnDifferenceCount
) {
    public static ShadowReadRowCheck of(String tableName, Instant readStartedAt, Instant readFinishedAt,
                                        List<String> resolvedIds, List<String> laggingIds, List<String> persistentIds,
                                        List<FieldDifference> columnDifferences) {
        return new ShadowReadRowCheck(tableName, readStartedAt, readFinishedAt, List.copyOf(resolvedIds),
            List.copyOf(laggingIds), List.copyOf(persistentIds), ShadowReadComparison.limit(columnDifferences),
            columnDifferences.size());
    }

    public ShadowReadResult result() {
        if (!persistentIds.isEmpty()) {
            return ShadowReadResult.MISMATCH_PERSISTENT;
        }
        if (!laggingIds.isEmpty()) {
            return ShadowReadResult.MISMATCH_LAGGING;
        }
        return ShadowReadResult.MISMATCH_RESOLVED;
    }
}
