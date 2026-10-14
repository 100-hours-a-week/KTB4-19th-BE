package com.homes.zipsai.migration.domain;

import java.util.List;

public record ShadowReadComparison(
    ShadowReadQuery v1Query,
    ShadowReadQuery v2Query,
    List<String> onlyInV1Ids,
    int onlyInV1Count,
    List<String> onlyInV2Ids,
    int onlyInV2Count,
    boolean orderDiffers,
    List<FieldDifference> fieldDifferences,
    int fieldDifferenceCount
) {
    public static final int MAX_LOGGED_DIFFERENCES = 20;

    public static ShadowReadComparison of(ShadowReadQuery v1Query, ShadowReadQuery v2Query, List<String> onlyInV1Ids,
                                          List<String> onlyInV2Ids, boolean orderDiffers,
                                          List<FieldDifference> fieldDifferences) {
        return new ShadowReadComparison(v1Query, v2Query, limit(onlyInV1Ids), onlyInV1Ids.size(),
            limit(onlyInV2Ids), onlyInV2Ids.size(), orderDiffers, limit(fieldDifferences), fieldDifferences.size());
    }

    public boolean isSame() {
        return onlyInV1Count == 0 && onlyInV2Count == 0 && !orderDiffers && fieldDifferenceCount == 0;
    }

    private static <T> List<T> limit(List<T> values) {
        return List.copyOf(values.subList(0, Math.min(values.size(), MAX_LOGGED_DIFFERENCES)));
    }

    public record FieldDifference(String id, String field, String v1Value, String v2Value) {
    }
}
