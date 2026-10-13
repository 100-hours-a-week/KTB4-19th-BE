package com.homes.zipsai.migration.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum MigrationDatabase {
    V1(OutboxDirection.V1_TO_V2),
    V2(OutboxDirection.V2_TO_V1);

    private final OutboxDirection outboxDirection;

    public MigrationDatabase other() {
        return this == V1 ? V2 : V1;
    }
}
