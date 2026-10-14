package com.homes.zipsai.migration.domain;

public record ShadowReadRequest(String traceId, String method, MigrationDatabase responseDatabase) {
}
