package com.homes.zipsai.migration.service;

import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.homes.zipsai.global.util.TimeUtils;
import com.homes.zipsai.migration.config.MigrationProperties;
import com.homes.zipsai.migration.domain.OutboxOperation;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class OutboxRecorder {

    private static final String INSERT_EVENT = """
        INSERT INTO Migration_outbox_events
            (direction, entity_type, entity_id, operation, status, retry_count, created_at)
        VALUES (:direction, :entityType, :entityId, :operation, 'PENDING', 0, :createdAt)
        """;

    private final JdbcClient jdbcClient;
    private final MigrationProperties migrationProperties;
    private final MigrationRouting migrationRouting;
    private final OutboxEntityTables outboxEntityTables;

    public void record(Class<?> entityClass, Object entityId, OutboxOperation operation) {
        if (!migrationProperties.isEnabled()) {
            return;
        }
        jdbcClient.sql(INSERT_EVENT)
            .param("direction", migrationRouting.determineWriteDatabase().getOutboxDirection().name())
            .param("entityType", outboxEntityTables.getTableName(entityClass))
            .param("entityId", String.valueOf(entityId))
            .param("operation", operation.name())
            .param("createdAt", TimeUtils.now())
            .update();
    }

    public void recordAll(Class<?> entityClass, List<?> entityIds, OutboxOperation operation) {
        for (Object entityId : entityIds) {
            record(entityClass, entityId, operation);
        }
    }
}
