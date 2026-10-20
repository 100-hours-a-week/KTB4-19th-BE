package com.homes.zipsai.migration.service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.homes.zipsai.migration.domain.MigrationSettings;
import com.homes.zipsai.migration.domain.OutboxDirection;
import com.homes.zipsai.migration.domain.OutboxEvent;
import com.homes.zipsai.migration.domain.OutboxStatus;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

@Component
@ConditionalOnBooleanProperty("app.migration.worker-enabled")
public class OutboxWorkerMetrics {

    private static final Logger LOGGER = LoggerFactory.getLogger(OutboxWorkerMetrics.class);
    private static final String COUNT_UNPROCESSED_EVENTS_BY_STATUS = """
        SELECT COUNT(*) FROM Migration_outbox_events
        WHERE direction = :direction AND processed_at IS NULL AND status = :status
        """;
    private static final String COUNT_PROCESSED_EVENTS = """
        SELECT COUNT(*) FROM Migration_outbox_events WHERE direction = :direction AND processed_at IS NOT NULL
        """;
    private static final String FIND_OLDEST_PENDING_CREATED_AT = """
        SELECT MIN(created_at) FROM Migration_outbox_events
        WHERE direction = :direction AND processed_at IS NULL AND status = 'PENDING'
        """;

    private final MeterRegistry meterRegistry;
    private final MigrationSettingsProvider migrationSettingsProvider;
    private final JdbcClient v1JdbcClient;
    private final JdbcClient v2JdbcClient;
    private final Map<OutboxDirection, Map<OutboxStatus, AtomicLong>> eventCounts = new EnumMap<>(
        OutboxDirection.class);
    private final Map<OutboxDirection, AtomicLong> oldestPendingSeconds = new EnumMap<>(OutboxDirection.class);

    public OutboxWorkerMetrics(MeterRegistry meterRegistry, MigrationSettingsProvider migrationSettingsProvider,
                               @Qualifier("outboxWorkerV1DataSource") DataSource outboxWorkerV1DataSource,
                               @Qualifier("outboxWorkerV2DataSource") DataSource outboxWorkerV2DataSource) {
        this.meterRegistry = meterRegistry;
        this.migrationSettingsProvider = migrationSettingsProvider;
        this.v1JdbcClient = JdbcClient.create(outboxWorkerV1DataSource);
        this.v2JdbcClient = JdbcClient.create(outboxWorkerV2DataSource);
        for (OutboxDirection direction : OutboxDirection.values()) {
            registerDirectionGauges(direction);
        }
    }

    public void refresh(LocalDateTime now) {
        try {
            for (OutboxDirection direction : OutboxDirection.values()) {
                refreshDirection(direction, now);
            }
        } catch (DataAccessException exception) {
            LOGGER.warn("이전 이벤트 지표를 갱신하지 못했습니다.", exception);
        }
    }

    public void recordApplied(OutboxEvent event, LocalDateTime now) {
        countWorkerEvent(event.direction(), "success");
        Timer.builder("migration.apply.lag")
            .tag("direction", event.direction().name())
            .publishPercentileHistogram()
            .register(meterRegistry)
            .record(Duration.between(event.createdAt(), now));
    }

    public void recordRetry(OutboxDirection direction) {
        countWorkerEvent(direction, "retry");
    }

    public void recordFailure(OutboxDirection direction) {
        countWorkerEvent(direction, "failure");
    }

    private void refreshDirection(OutboxDirection direction, LocalDateTime now) {
        JdbcClient source = sourceOf(direction);
        for (OutboxStatus status : OutboxStatus.values()) {
            eventCounts.get(direction).get(status).set(countEvents(source, direction, status));
        }
        LocalDateTime oldestCreatedAt = source.sql(FIND_OLDEST_PENDING_CREATED_AT)
            .param("direction", direction.name())
            .query(LocalDateTime.class)
            .optional()
            .orElse(null);
        long waitingSeconds = oldestCreatedAt == null ? 0 : Duration.between(oldestCreatedAt, now).toSeconds();
        oldestPendingSeconds.get(direction).set(Math.max(waitingSeconds, 0));
    }

    private void registerDirectionGauges(OutboxDirection direction) {
        Gauge.builder("migration.worker.active", () -> isWorkerActive(direction) ? 1 : 0)
            .tag("direction", direction.name())
            .register(meterRegistry);
        Map<OutboxStatus, AtomicLong> counts = new EnumMap<>(OutboxStatus.class);
        for (OutboxStatus status : OutboxStatus.values()) {
            AtomicLong count = new AtomicLong();
            counts.put(status, count);
            Gauge.builder("migration.outbox.events", count, AtomicLong::get)
                .tag("direction", direction.name())
                .tag("status", status.name())
                .register(meterRegistry);
        }
        eventCounts.put(direction, counts);
        AtomicLong waitingSeconds = new AtomicLong();
        oldestPendingSeconds.put(direction, waitingSeconds);
        Gauge.builder("migration.outbox.oldest.pending", waitingSeconds, AtomicLong::get)
            .tag("direction", direction.name())
            .baseUnit("seconds")
            .register(meterRegistry);
    }

    private boolean isWorkerActive(OutboxDirection direction) {
        MigrationSettings settings = migrationSettingsProvider.getSettings();
        return settings.isWorkerRunning() && settings.isWorkerEnabled(direction);
    }

    private long countEvents(JdbcClient source, OutboxDirection direction, OutboxStatus status) {
        if (status == OutboxStatus.COMPLETED) {
            return source.sql(COUNT_PROCESSED_EVENTS)
                .param("direction", direction.name())
                .query(Long.class)
                .single();
        }
        return source.sql(COUNT_UNPROCESSED_EVENTS_BY_STATUS)
            .param("direction", direction.name())
            .param("status", status.name())
            .query(Long.class)
            .single();
    }

    private void countWorkerEvent(OutboxDirection direction, String outcome) {
        meterRegistry.counter("migration.worker.events", "direction", direction.name(), "outcome", outcome)
            .increment();
    }

    private JdbcClient sourceOf(OutboxDirection direction) {
        return direction == OutboxDirection.V1_TO_V2 ? v1JdbcClient : v2JdbcClient;
    }
}
