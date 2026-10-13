package com.homes.zipsai.migration.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum MigrationMode {
    V1_PRIMARY(MigrationDatabase.V1),
    V2_PRIMARY(MigrationDatabase.V2);

    private final MigrationDatabase primaryDatabase;

    public MigrationMode opposite() {
        return this == V1_PRIMARY ? V2_PRIMARY : V1_PRIMARY;
    }
}
