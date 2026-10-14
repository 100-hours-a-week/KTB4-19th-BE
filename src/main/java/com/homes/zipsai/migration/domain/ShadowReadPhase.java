package com.homes.zipsai.migration.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ShadowReadPhase {
    INITIAL("initial"),
    RECOMPARE("recompare");

    private final String tag;
}
