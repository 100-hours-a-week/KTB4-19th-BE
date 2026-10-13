package com.homes.zipsai.migration.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ShadowReadResult {
    MATCH("match"),
    MISMATCH_TRANSIENT("mismatch_transient"),
    MISMATCH_PERSISTENT("mismatch_persistent"),
    ERROR("error");

    private final String tag;
}
