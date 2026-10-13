package com.homes.zipsai.migration.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ShadowReadResult {
    MATCH("match"),
    MISMATCH_INITIAL("mismatch_initial"),
    MISMATCH_PERSISTENT("mismatch_persistent"),
    ERROR("error");

    private final String tag;
}
