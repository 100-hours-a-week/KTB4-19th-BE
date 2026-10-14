package com.homes.zipsai.migration.domain;

import java.time.Instant;

public record ShadowReadQuery(MigrationDatabase database, Instant startedAt, Instant finishedAt, Object result) {
}
