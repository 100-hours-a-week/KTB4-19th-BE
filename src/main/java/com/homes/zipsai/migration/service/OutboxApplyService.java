package com.homes.zipsai.migration.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.homes.zipsai.migration.domain.OutboxEvent;

@Service
@ConditionalOnBooleanProperty("app.migration.worker-enabled")
public class OutboxApplyService {

    private static final String COUNT_APPLIED_EVENT = """
        SELECT (SELECT COUNT(*) FROM Migration_applied_events WHERE direction = :direction AND event_id = :eventId)
            + (SELECT COUNT(*) FROM Migration_outbox_events WHERE direction = :direction AND event_id = :eventId)
        """;
    private static final String INSERT_APPLIED_EVENT = """
        INSERT INTO Migration_applied_events (direction, event_id, applied_at) VALUES (:direction, :eventId, :now)
        """;
    private static final String NEW_ROW_ALIAS = "new_row";

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
    public void applyV1Event(OutboxEvent event, LocalDateTime now) {
        apply(event, v1JdbcClient, v2JdbcClient, now);
    }

    @Transactional(transactionManager = "outboxWorkerV1TransactionManager")
    public void applyV2Event(OutboxEvent event, LocalDateTime now) {
        apply(event, v2JdbcClient, v1JdbcClient, now);
    }

    private void apply(OutboxEvent event, JdbcClient source, JdbcClient target, LocalDateTime now) {
        if (isApplied(event, target)) {
            return;
        }
        String tableName = event.entityType();
        String idColumn = outboxEntityTables.getIdColumn(tableName);
        Map<String, Object> sourceRow = findRow(source, tableName, idColumn, event.entityId());
        if (sourceRow == null) {
            target.sql("DELETE FROM " + quote(tableName) + " WHERE " + quote(idColumn) + " = :entityId")
                .param("entityId", event.entityId())
                .update();
        } else {
            upsert(target, tableName, sourceRow);
        }
        target.sql(INSERT_APPLIED_EVENT)
            .param("direction", event.direction().name())
            .param("eventId", event.eventId())
            .param("now", now)
            .update();
    }

    private boolean isApplied(OutboxEvent event, JdbcClient target) {
        Long count = target.sql(COUNT_APPLIED_EVENT)
            .param("direction", event.direction().name())
            .param("eventId", event.eventId())
            .query(Long.class)
            .single();
        return count > 0;
    }

    private Map<String, Object> findRow(JdbcClient database, String tableName, String idColumn, String entityId) {
        List<Map<String, Object>> rows = database
            .sql("SELECT * FROM " + quote(tableName) + " WHERE " + quote(idColumn) + " = :entityId")
            .param("entityId", entityId)
            .query()
            .listOfRows();
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private void upsert(JdbcClient target, String tableName, Map<String, Object> row) {
        String columns = row.keySet().stream()
            .map(OutboxApplyService::quote)
            .collect(Collectors.joining(", "));
        String placeholders = row.keySet().stream()
            .map(column -> "?")
            .collect(Collectors.joining(", "));
        String updates = row.keySet().stream()
            .map(column -> quote(column) + " = " + NEW_ROW_ALIAS + "." + quote(column))
            .collect(Collectors.joining(", "));
        String sql = "INSERT INTO %s (%s) VALUES (%s) AS %s ON DUPLICATE KEY UPDATE %s"
            .formatted(quote(tableName), columns, placeholders, NEW_ROW_ALIAS, updates);
        target.sql(sql)
            .params(row.values().stream().toList())
            .update();
    }

    private static String quote(String identifier) {
        return "`" + identifier.replace("`", "``") + "`";
    }
}
