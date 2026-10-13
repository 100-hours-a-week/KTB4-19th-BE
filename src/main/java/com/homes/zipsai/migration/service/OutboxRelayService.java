package com.homes.zipsai.migration.service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

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
        SELECT event_id, direction, entity_type, entity_id, status, retry_count, next_retry_at
        FROM Migration_outbox_events
        WHERE direction = :direction AND processed_at IS NULL
        ORDER BY event_id
        LIMIT :limit
        """;
    private static final String MARK_COMPLETED = """
        UPDATE Migration_outbox_events SET status = 'COMPLETED', processed_at = :now WHERE event_id = :eventId
        """;
    private static final String MARK_RETRY = """
        UPDATE Migration_outbox_events
        SET status = :status, retry_count = :retryCount, next_retry_at = :nextRetryAt, last_error = :lastError
        WHERE event_id = :eventId
        """;

    private final JdbcClient v1JdbcClient;
    private final JdbcClient v2JdbcClient;
    private final OutboxApplyService outboxApplyService;
    private final MigrationSettingsProvider migrationSettingsProvider;

    public OutboxRelayService(@Qualifier("outboxWorkerV1DataSource") DataSource outboxWorkerV1DataSource,
                              @Qualifier("outboxWorkerV2DataSource") DataSource outboxWorkerV2DataSource,
                              OutboxApplyService outboxApplyService,
                              MigrationSettingsProvider migrationSettingsProvider) {
        this.v1JdbcClient = JdbcClient.create(outboxWorkerV1DataSource);
        this.v2JdbcClient = JdbcClient.create(outboxWorkerV2DataSource);
        this.outboxApplyService = outboxApplyService;
        this.migrationSettingsProvider = migrationSettingsProvider;
    }

    public void relayPendingEvents(LocalDateTime now) {
        if (!migrationSettingsProvider.getSettings().isWorkerRunning()) {
            return;
        }
        relayEventsOfOneDirection(now);
    }

    private void relayEventsOfOneDirection(LocalDateTime now) {
        MigrationSettings settings = migrationSettingsProvider.getSettings();
        MigrationMode mode = settings.modeAt(TimeUtils.toOffsetDateTime(now));
        OutboxDirection writeDirection = mode.getPrimaryDatabase().getOutboxDirection();
        for (OutboxDirection direction : List.of(writeDirection.reverse(), writeDirection)) {
            if (!settings.isWorkerEnabled(direction)) {
                continue;
            }
            List<OutboxEvent> events = findUnprocessedEvents(direction);
            if (!events.isEmpty()) {
                relayEvents(events, mode, now);
                return;
            }
        }
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

    private void relayEvents(List<OutboxEvent> events, MigrationMode mode, LocalDateTime now) {
        int relayedCount = 0;
        for (OutboxEvent event : events) {
            if (!canContinue(mode, event.direction(), now) || !relay(event, now)) {
                break;
            }
            relayedCount++;
        }
        if (relayedCount > 0) {
            OutboxEvent lastEvent = events.get(relayedCount - 1);
            LOGGER.info("이전 이벤트를 반영했습니다. direction={}, count={}, lastEventId={}", lastEvent.direction(),
                relayedCount, lastEvent.eventId());
        }
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
        if (event.isFailed() || event.isWaitingForRetry(now)) {
            return false;
        }
        JdbcClient source = sourceOf(event.direction());
        try {
            if (event.direction() == OutboxDirection.V1_TO_V2) {
                outboxApplyService.applyV1Event(event, now);
            } else {
                outboxApplyService.applyV2Event(event, now);
            }
            source.sql(MARK_COMPLETED)
                .param("now", now)
                .param("eventId", event.eventId())
                .update();
            return true;
        } catch (RuntimeException exception) {
            markRetry(event, source, now, exception);
            return false;
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
            LOGGER.error("이전 이벤트가 {}번 실패해 워커를 멈춥니다. eventId={}", MAX_RETRY_COUNT, event.eventId(), exception);
            return;
        }
        LOGGER.warn("이전 이벤트 반영에 실패해 {}초 뒤 다시 시도합니다. eventId={}, retryCount={}",
            RETRY_DELAY.toSeconds(), event.eventId(), retryCount, exception);
    }
}
