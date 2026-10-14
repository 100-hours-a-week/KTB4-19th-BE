package com.homes.zipsai.migration.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ShadowReadResult {
    MATCH("match"),
    MISMATCH_INITIAL("mismatch_initial"),
    MISMATCH_RESOLVED("mismatch_resolved"),
    MISMATCH_LAGGING("mismatch_lagging"),
    MISMATCH_PERSISTENT("mismatch_persistent"),
    ERROR("error"),
    SKIPPED("skipped");

    private final String tag;
}
