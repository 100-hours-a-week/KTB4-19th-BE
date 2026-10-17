package com.homes.zipsai.migration.service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.homes.zipsai.global.util.TextUtils;
import com.homes.zipsai.global.util.TimeUtils;
import com.homes.zipsai.migration.domain.MigrationMode;
import com.homes.zipsai.migration.domain.MigrationSettings;
import com.homes.zipsai.migration.domain.OutboxDirection;
import com.homes.zipsai.migration.domain.OutboxEvent;
import com.homes.zipsai.migration.domain.OutboxStatus;

@Service
@ConditionalOnBooleanProperty("app.migration.worker-enabled")
public class OutboxRelayService {

    private static final Logger LOGGER = LoggerFactory.getLogger(OutboxRelayService.class);
    private static final int BATCH_SIZE = 100;
    private static final int MAX_RETRY_COUNT = 5;
    private static final Duration RETRY_DELAY = Duration.ofSeconds(10);
    private static final int LAST_ERROR_MAX_LENGTH = 1000;

    private static final String SELECT_UNPROCESSED_EVENTS = """
        SELECT event_id, direction, entity_type, entity_id, status, retry_count, next_retry_at, created_at
        FROM Migration_outbox_events
        WHERE direction = :direction AND processed_at IS NULL
        ORDER BY event_id
        LIMIT :limit
        """;
    private static final String COUNT_UNPROCESSED_EVENTS = """
        SELECT COUNT(*) FROM Migration_outbox_events
        WHERE direction = :direction AND processed_at IS NULL AND status = :status
        """;
    private static final String ACQUIRE_LOCK = "SELECT GET_LOCK(?, 0)";
    private static final String RELEASE_LOCK = "SELECT RELEASE_LOCK(?)";
    private static final String LOCK_NAME = "migration_outbox_worker";
    private static final String MARK_COMPLETED = """
        UPDATE Migration_outbox_events SET status = 'COMPLETED', processed_at = :completedAt
        WHERE event_id IN (:eventIds)
        """;
    private static final String MARK_RETRY = """
        UPDATE Migration_outbox_events
        SET status = :status, retry_count = :retryCount, next_retry_at = :nextRetryAt, last_error = :lastError
        WHERE event_id = :eventId
        """;

    private final DataSource lockDataSource;
    private final JdbcClient v1JdbcClient;
    private final JdbcClient v2JdbcClient;
    private final OutboxApplyService outboxApplyService;
    private final MigrationSettingsProvider migrationSettingsProvider;
    private final OutboxWorkerMetrics outboxWorkerMetrics;
    private final MigrationEventLogger migrationEventLogger;
    private final Map<OutboxDirection, Long> lastUnprocessedCounts = new EnumMap<>(OutboxDirection.class);

    public OutboxRelayService(@Qualifier("outboxWorkerV1DataSource") DataSource outboxWorkerV1DataSource,
                              @Qualifier("outboxWorkerV2DataSource") DataSource outboxWorkerV2DataSource,
                              OutboxApplyService outboxApplyService,
                              MigrationSettingsProvider migrationSettingsProvider,
                              OutboxWorkerMetrics outboxWorkerMetrics,
                              MigrationEventLogger migrationEventLogger) {
        this.lockDataSource = outboxWorkerV1DataSource;
        this.v1JdbcClient = JdbcClient.create(outboxWorkerV1DataSource);
        this.v2JdbcClient = JdbcClient.create(outboxWorkerV2DataSource);
        this.outboxApplyService = outboxApplyService;
        this.migrationSettingsProvider = migrationSettingsProvider;
        this.outboxWorkerMetrics = outboxWorkerMetrics;
        this.migrationEventLogger = migrationEventLogger;
    }

    public boolean relayPendingEvents(LocalDateTime now) {
        if (!migrationSettingsProvider.getSettings().isWorkerRunning()) {
            return false;
        }
        try (Connection lockConnection = lockDataSource.getConnection()) {
            if (!acquireLock(lockConnection)) {
                return false;
            }
            try {
                return relayEventsOfOneDirection(now) == BATCH_SIZE;
            } finally {
                releaseLock(lockConnection);
            }
        } catch (SQLException exception) {
            LOGGER.warn("워커 락을 잡지 못해 이번 회차를 건너뜁니다.", exception);
            return false;
        }
    }

    public void logUnprocessedCounts() {
        for (OutboxDirection direction : OutboxDirection.values()) {
            long pendingCount = countUnprocessedEvents(direction, OutboxStatus.PENDING);
            long failedCount = countUnprocessedEvents(direction, OutboxStatus.FAILED);
            LOGGER.info("처리되지 않은 이전 이벤트 건수입니다. direction={}, pending={}, failed={}", direction,
                pendingCount, failedCount);
            Long lastCount = lastUnprocessedCounts.put(direction, pendingCount + failedCount);
            if (pendingCount + failedCount == 0 && lastCount != null && lastCount > 0) {
                LOGGER.info("처리되지 않은 이전 이벤트가 0건이 됐습니다. direction={}", direction);
            }
        }
    }

    private boolean acquireLock(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(ACQUIRE_LOCK)) {
            statement.setString(1, LOCK_NAME);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() && resultSet.getInt(1) == 1;
            }
        }
    }

    private void releaseLock(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(RELEASE_LOCK)) {
            statement.setString(1, LOCK_NAME);
            statement.execute();
        }
    }

    private int relayEventsOfOneDirection(LocalDateTime now) {
        MigrationSettings settings = migrationSettingsProvider.getSettings();
        MigrationMode mode = settings.modeAt(TimeUtils.toOffsetDateTime(now));
        OutboxDirection writeDirection = mode.getPrimaryDatabase().getOutboxDirection();
        for (OutboxDirection direction : List.of(writeDirection.reverse(), writeDirection)) {
            if (!settings.isWorkerEnabled(direction)) {
                continue;
            }
            List<OutboxEvent> events = findUnprocessedEvents(direction);
            if (!events.isEmpty()) {
                return relayEvents(events, mode, now);
            }
        }
        return 0;
    }

    private long countUnprocessedEvents(OutboxDirection direction, OutboxStatus status) {
        return sourceOf(direction).sql(COUNT_UNPROCESSED_EVENTS)
            .param("direction", direction.name())
            .param("status", status.name())
            .query(Long.class)
            .single();
    }

    private List<OutboxEvent> findUnprocessedEvents(OutboxDirection direction) {
        return sourceOf(direction).sql(SELECT_UNPROCESSED_EVENTS)
            .param("direction", direction.name())
            .param("limit", BATCH_SIZE)
            .query(OutboxEvent.class)
            .list();
    }

    private JdbcClient sourceOf(OutboxDirection direction) {
        return direction == OutboxDirection.V1_TO_V2 ? v1JdbcClient : v2JdbcClient;
    }

    private int relayEvents(List<OutboxEvent> events, MigrationMode mode, LocalDateTime now) {
        List<OutboxEvent> relayableEvents = events.stream()
            .takeWhile(event -> !event.isFailed() && !event.isWaitingForRetry(now))
            .toList();
        if (relayableEvents.isEmpty() || !canContinue(mode, relayableEvents.getFirst().direction(), now)) {
            return 0;
        }
        int relayedCount = relayTogether(relayableEvents)
            ? relayableEvents.size()
            : relayOneByOne(relayableEvents, mode, now);
        if (relayedCount > 0) {
            OutboxEvent lastEvent = relayableEvents.get(relayedCount - 1);
            LOGGER.info("이전 이벤트를 반영했습니다. direction={}, count={}, lastEventId={}", lastEvent.direction(),
                relayedCount, lastEvent.eventId());
        }
        return relayedCount;
    }

    private boolean relayTogether(List<OutboxEvent> events) {
        try {
            applyAndComplete(events);
            return true;
        } catch (RuntimeException exception) {
            LOGGER.warn("이전 이벤트를 묶어서 반영하지 못해 한 건씩 다시 반영합니다. firstEventId={}, count={}",
                events.getFirst().eventId(), events.size(), exception);
            return false;
        }
    }

    private int relayOneByOne(List<OutboxEvent> events, MigrationMode mode, LocalDateTime now) {
        int relayedCount = 0;
        for (OutboxEvent event : events) {
            if (!canContinue(mode, event.direction(), now) || !relay(event, now)) {
                break;
            }
            relayedCount++;
        }
        return relayedCount;
    }

    private boolean canContinue(MigrationMode mode, OutboxDirection direction, LocalDateTime now) {
        MigrationSettings settings = migrationSettingsProvider.getSettings();
        MigrationMode currentMode = settings.modeAt(TimeUtils.toOffsetDateTime(now));
        if (settings.isWorkerRunning() && settings.isWorkerEnabled(direction) && currentMode == mode) {
            return true;
        }
        LOGGER.info("이전 설정이 바뀌어 처리를 멈춥니다. mode={}, workerMode={}, direction={}", currentMode,
            settings.workerMode(), direction);
        return false;
    }

    private boolean relay(OutboxEvent event, LocalDateTime now) {
        try {
            applyAndComplete(List.of(event));
            return true;
        } catch (RuntimeException exception) {
            markRetry(event, sourceOf(event.direction()), now, exception);
            return false;
        }
    }

    private void applyAndComplete(List<OutboxEvent> events) {
        OutboxDirection direction = events.getFirst().direction();
        long started = System.nanoTime();
        if (direction == OutboxDirection.V1_TO_V2) {
            outboxApplyService.applyV1Events(events);
        } else {
            outboxApplyService.applyV2Events(events);
        }
        LocalDateTime completedAt = TimeUtils.now();
        sourceOf(direction).sql(MARK_COMPLETED)
            .param("completedAt", completedAt)
            .param("eventIds", events.stream().map(OutboxEvent::eventId).toList())
            .update();
        Duration batchDuration = Duration.ofNanos(System.nanoTime() - started);
        for (OutboxEvent event : events) {
            outboxWorkerMetrics.recordApplied(event, completedAt);
            migrationEventLogger.outboxApplied(event, batchDuration, events.size(),
                Duration.between(event.createdAt(), completedAt).toMillis());
        }
    }

    private void markRetry(OutboxEvent event, JdbcClient source, LocalDateTime now, RuntimeException exception) {
        int retryCount = event.retryCount() + 1;
        OutboxStatus status = retryCount >= MAX_RETRY_COUNT ? OutboxStatus.FAILED : OutboxStatus.PENDING;
        String lastError = TextUtils.truncate(String.valueOf(exception), LAST_ERROR_MAX_LENGTH);
        source.sql(MARK_RETRY)
            .param("status", status.name())
            .param("retryCount", retryCount)
            .param("nextRetryAt", now.plus(RETRY_DELAY))
            .param("lastError", lastError)
            .param("eventId", event.eventId())
            .update();
        if (status == OutboxStatus.FAILED) {
            outboxWorkerMetrics.recordFailure(event.direction());
            migrationEventLogger.outboxFailed(event, retryCount, lastError);
            LOGGER.error("이전 이벤트가 {}번 실패해 워커를 멈춥니다. eventId={}", MAX_RETRY_COUNT, event.eventId(), exception);
            return;
        }
        outboxWorkerMetrics.recordRetry(event.direction());
        migrationEventLogger.outboxRetry(event, retryCount, lastError);
        LOGGER.warn("이전 이벤트 반영에 실패해 {}초 뒤 다시 시도합니다. eventId={}, retryCount={}",
            RETRY_DELAY.toSeconds(), event.eventId(), retryCount, exception);
    }
}
