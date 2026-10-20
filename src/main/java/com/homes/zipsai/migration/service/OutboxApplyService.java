package com.homes.zipsai.migration.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.homes.zipsai.global.util.TimeUtils;
import com.homes.zipsai.migration.domain.OutboxDirection;
import com.homes.zipsai.migration.domain.OutboxEvent;

@Service
@ConditionalOnBooleanProperty("app.migration.worker-enabled")
public class OutboxApplyService {

    private static final Logger LOGGER = LoggerFactory.getLogger(OutboxApplyService.class);
    private static final String SELECT_APPLIED_EVENT_IDS = """
        SELECT event_id FROM Migration_applied_events WHERE direction = :direction AND event_id IN (:eventIds)
        UNION
        SELECT event_id FROM Migration_outbox_events WHERE direction = :direction AND event_id IN (:eventIds)
        """;
    private static final String INSERT_APPLIED_EVENTS =
        "INSERT INTO Migration_applied_events (direction, event_id, applied_at) VALUES ";
    private static final int APPLIED_EVENT_COLUMN_COUNT = 3;
    private static final String NEW_ROW_ALIAS = "new_row";
    private static final String UPDATED_AT_COLUMN = "updated_at";
    private static final String REFRESH_SESSIONS_TABLE = "Refresh_sessions";
    private static final String REVOKED_COLUMN = "revoked";

    private final JdbcClient v1JdbcClient;
    private final JdbcClient v2JdbcClient;
    private final OutboxEntityTables outboxEntityTables;

    public OutboxApplyService(@Qualifier("outboxWorkerV1DataSource") DataSource outboxWorkerV1DataSource,
                              @Qualifier("outboxWorkerV2DataSource") DataSource outboxWorkerV2DataSource,
                              OutboxEntityTables outboxEntityTables) {
        this.v1JdbcClient = JdbcClient.create(outboxWorkerV1DataSource);
        this.v2JdbcClient = JdbcClient.create(outboxWorkerV2DataSource);
        this.outboxEntityTables = outboxEntityTables;
    }

    @Transactional(transactionManager = "outboxWorkerV2TransactionManager")
    public void applyV1Events(List<OutboxEvent> events) {
        apply(events, v1JdbcClient, v2JdbcClient);
    }

    @Transactional(transactionManager = "outboxWorkerV1TransactionManager")
    public void applyV2Events(List<OutboxEvent> events) {
        apply(events, v2JdbcClient, v1JdbcClient);
    }

    private void apply(List<OutboxEvent> events, JdbcClient source, JdbcClient target) {
        OutboxDirection direction = events.getFirst().direction();
        Set<Long> appliedEventIds = findAppliedEventIds(target, direction, events);
        List<OutboxEvent> unappliedEvents = events.stream()
            .filter(event -> !appliedEventIds.contains(event.eventId()))
            .toList();
        if (unappliedEvents.isEmpty()) {
            return;
        }
        Map<String, Set<String>> entityIdsByTable = unappliedEvents.stream()
            .collect(Collectors.groupingBy(OutboxEvent::entityType, LinkedHashMap::new,
                Collectors.mapping(OutboxEvent::entityId, Collectors.toCollection(LinkedHashSet::new))));
        entityIdsByTable.forEach((tableName, entityIds) -> applyRows(source, target, direction, tableName, entityIds));
        insertAppliedEvents(target, direction, unappliedEvents);
    }

    private Set<Long> findAppliedEventIds(JdbcClient target, OutboxDirection direction, List<OutboxEvent> events) {
        return Set.copyOf(target.sql(SELECT_APPLIED_EVENT_IDS)
            .param("direction", direction.name())
            .param("eventIds", events.stream().map(OutboxEvent::eventId).toList())
            .query(Long.class)
            .list());
    }

    private void applyRows(JdbcClient source, JdbcClient target, OutboxDirection direction, String tableName,
                           Set<String> entityIds) {
        String idColumn = outboxEntityTables.getIdColumn(tableName);
        Map<String, Map<String, Object>> sourceRows = findRows(source, tableName, idColumn, entityIds);
        Map<String, Map<String, Object>> targetRows = findRows(target, tableName, idColumn, entityIds);
        List<String> missingIds = entityIds.stream()
            .filter(entityId -> !sourceRows.containsKey(entityId))
            .toList();
        List<Map<String, Object>> upsertRows = new ArrayList<>();
        sourceRows.forEach((entityId, sourceRow) -> {
            if (keepsTargetRow(tableName, sourceRow, targetRows.get(entityId))) {
                LOGGER.warn("대상 DB의 행이 더 최신이라 덮어쓰지 않습니다. direction={}, table={}, id={}", direction,
                    tableName, entityId);
                return;
            }
            upsertRows.add(sourceRow);
        });
        delete(target, tableName, idColumn, missingIds);
        upsert(target, tableName, upsertRows);
    }

    private Map<String, Map<String, Object>> findRows(JdbcClient database, String tableName, String idColumn,
                                                      Collection<String> entityIds) {
        return database.sql("SELECT * FROM " + quote(tableName) + " WHERE " + quote(idColumn) + " IN (:entityIds)")
            .param("entityIds", List.copyOf(entityIds))
            .query()
            .listOfRows()
            .stream()
            .collect(Collectors.toMap(row -> String.valueOf(row.get(idColumn)), row -> row));
    }

    private boolean keepsTargetRow(String tableName, Map<String, Object> sourceRow, Map<String, Object> targetRow) {
        if (targetRow == null) {
            return false;
        }
        if (REFRESH_SESSIONS_TABLE.equals(tableName)) {
            return isRevoked(targetRow) && !isRevoked(sourceRow);
        }
        return isNewer(targetRow.get(UPDATED_AT_COLUMN), sourceRow.get(UPDATED_AT_COLUMN));
    }

    private static boolean isRevoked(Map<String, Object> row) {
        Object revoked = row.get(REVOKED_COLUMN);
        return Boolean.TRUE.equals(revoked) || revoked instanceof Number number && number.intValue() == 1;
    }

    private static boolean isNewer(Object targetUpdatedAt, Object sourceUpdatedAt) {
        return targetUpdatedAt instanceof LocalDateTime targetTime
            && sourceUpdatedAt instanceof LocalDateTime sourceTime
            && targetTime.isAfter(sourceTime);
    }

    private void delete(JdbcClient target, String tableName, String idColumn, List<String> entityIds) {
        if (entityIds.isEmpty()) {
            return;
        }
        target.sql("DELETE FROM " + quote(tableName) + " WHERE " + quote(idColumn) + " IN (:entityIds)")
            .param("entityIds", entityIds)
            .update();
    }

    private void upsert(JdbcClient target, String tableName, List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return;
        }
        Set<String> columnNames = rows.getFirst().keySet();
        String columns = columnNames.stream()
            .map(OutboxApplyService::quote)
            .collect(Collectors.joining(", "));
        String values = rows.stream()
            .map(row -> placeholders(columnNames.size()))
            .collect(Collectors.joining(", "));
        String updates = columnNames.stream()
            .map(column -> quote(column) + " = " + NEW_ROW_ALIAS + "." + quote(column))
            .collect(Collectors.joining(", "));
        String sql = "INSERT INTO %s (%s) VALUES %s AS %s ON DUPLICATE KEY UPDATE %s"
            .formatted(quote(tableName), columns, values, NEW_ROW_ALIAS, updates);
        target.sql(sql)
            .params(rows.stream().flatMap(row -> row.values().stream()).toList())
            .update();
    }

    private void insertAppliedEvents(JdbcClient target, OutboxDirection direction, List<OutboxEvent> events) {
        LocalDateTime appliedAt = TimeUtils.now();
        String values = events.stream()
            .map(event -> placeholders(APPLIED_EVENT_COLUMN_COUNT))
            .collect(Collectors.joining(", "));
        target.sql(INSERT_APPLIED_EVENTS + values)
            .params(events.stream()
                .flatMap(event -> Stream.of(direction.name(), event.eventId(), appliedAt))
                .toList())
            .update();
    }

    private static String placeholders(int count) {
        return "(" + String.join(", ", Collections.nCopies(count, "?")) + ")";
    }

    private static String quote(String identifier) {
        return "`" + identifier.replace("`", "``") + "`";
    }
}
