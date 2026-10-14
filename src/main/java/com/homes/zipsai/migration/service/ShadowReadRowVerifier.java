package com.homes.zipsai.migration.service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.homes.zipsai.global.util.TimeUtils;
import com.homes.zipsai.migration.config.ConditionalOnMigrationEnabled;
import com.homes.zipsai.migration.config.MigrationRoutingConfiguration.MigrationDataSources;
import com.homes.zipsai.migration.domain.OutboxDirection;
import com.homes.zipsai.migration.domain.ShadowReadComparison;
import com.homes.zipsai.migration.domain.ShadowReadComparison.FieldDifference;
import com.homes.zipsai.migration.domain.ShadowReadRowCheck;

@Component
@ConditionalOnMigrationEnabled
public class ShadowReadRowVerifier {

    private static final String COUNT_UNAPPLIED_EVENTS = """
        SELECT COUNT(*) FROM Migration_outbox_events
        WHERE direction = :direction AND entity_type = :entityType AND entity_id = :entityId
            AND (status = 'PENDING' OR processed_at >= :readStartedAt)
        """;
    private static final String PRESENT_FIELD = "present";

    private final JdbcClient v1JdbcClient;
    private final JdbcClient v2JdbcClient;
    private final OutboxEntityTables outboxEntityTables;

    public ShadowReadRowVerifier(@Qualifier("migrationDataSources") MigrationDataSources migrationDataSources,
                                 OutboxEntityTables outboxEntityTables) {
        this.v1JdbcClient = JdbcClient.create(migrationDataSources.v1());
        this.v2JdbcClient = JdbcClient.create(migrationDataSources.v2());
        this.outboxEntityTables = outboxEntityTables;
    }

    public Optional<ShadowReadRowCheck> verify(ShadowReadComparison comparison) {
        List<String> ids = comparison.differingEntityIds();
        if (ids.isEmpty()) {
            return Optional.empty();
        }
        return outboxEntityTables.findTableNameByIdField(comparison.idField())
            .map(tableName -> verifyRows(tableName, ids));
    }

    private ShadowReadRowCheck verifyRows(String tableName, List<String> ids) {
        String idColumn = outboxEntityTables.getIdColumn(tableName);
        LocalDateTime readStartedAt = TimeUtils.now();
        Instant startedAt = Instant.now();
        List<String> resolvedIds = new ArrayList<>();
        List<String> laggingIds = new ArrayList<>();
        List<String> persistentIds = new ArrayList<>();
        List<FieldDifference> columnDifferences = new ArrayList<>();
        for (String id : ids) {
            Map<String, Object> v1Row = findRow(v1JdbcClient, tableName, idColumn, id);
            Map<String, Object> v2Row = findRow(v2JdbcClient, tableName, idColumn, id);
            List<FieldDifference> differences = differencesOf(id, v1Row, v2Row);
            if (differences.isEmpty()) {
                resolvedIds.add(id);
            } else if (hasUnappliedEvent(tableName, id, readStartedAt)) {
                laggingIds.add(id);
            } else {
                persistentIds.add(id);
                columnDifferences.addAll(differences);
            }
        }
        return ShadowReadRowCheck.of(tableName, startedAt, Instant.now(), resolvedIds, laggingIds, persistentIds,
            columnDifferences);
    }

    private static Map<String, Object> findRow(JdbcClient database, String tableName, String idColumn, String id) {
        List<Map<String, Object>> rows = database
            .sql("SELECT * FROM " + quote(tableName) + " WHERE " + quote(idColumn) + " = :id")
            .param("id", id)
            .query()
            .listOfRows();
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private static List<FieldDifference> differencesOf(String id, Map<String, Object> v1Row,
                                                       Map<String, Object> v2Row) {
        if (v1Row == null || v2Row == null) {
            return v1Row == v2Row ? List.of() : List.of(new FieldDifference(id, PRESENT_FIELD,
                String.valueOf(v1Row != null), String.valueOf(v2Row != null)));
        }
        Set<String> columns = new LinkedHashSet<>(v1Row.keySet());
        columns.addAll(v2Row.keySet());
        List<FieldDifference> differences = new ArrayList<>();
        for (String column : columns) {
            if (!Objects.deepEquals(v1Row.get(column), v2Row.get(column))) {
                differences.add(new FieldDifference(id, column, loggedValue(column, v1Row.get(column)),
                    loggedValue(column, v2Row.get(column))));
            }
        }
        return differences;
    }

    private static String loggedValue(String column, Object value) {
        return switch (value) {
            case null -> null;
            case byte[] bytes -> ShadowReadValues.mask(column, null, HexFormat.of().formatHex(bytes));
            default -> ShadowReadValues.mask(column, String.valueOf(value), String.valueOf(value));
        };
    }

    private boolean hasUnappliedEvent(String tableName, String id, LocalDateTime readStartedAt) {
        return countUnappliedEvents(v1JdbcClient, OutboxDirection.V1_TO_V2, tableName, id, readStartedAt)
            + countUnappliedEvents(v2JdbcClient, OutboxDirection.V2_TO_V1, tableName, id, readStartedAt) > 0;
    }

    private static long countUnappliedEvents(JdbcClient database, OutboxDirection direction, String tableName,
                                             String id, LocalDateTime readStartedAt) {
        return database.sql(COUNT_UNAPPLIED_EVENTS)
            .param("direction", direction.name())
            .param("entityType", tableName)
            .param("entityId", id)
            .param("readStartedAt", readStartedAt)
            .query(Long.class)
            .single();
    }

    private static String quote(String identifier) {
        return "`" + identifier.replace("`", "``") + "`";
    }
}
