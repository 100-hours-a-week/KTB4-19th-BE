package com.homes.zipsai.migration.service;

import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.homes.zipsai.global.util.TextUtils;
import com.homes.zipsai.migration.domain.MigrationDatabase;
import com.homes.zipsai.migration.domain.MigrationMode;
import com.homes.zipsai.migration.domain.MigrationSettings;
import com.homes.zipsai.migration.domain.OutboxDirection;
import com.homes.zipsai.migration.domain.OutboxEvent;
import com.homes.zipsai.migration.domain.ShadowReadComparison;
import com.homes.zipsai.migration.domain.ShadowReadComparison.FieldDifference;
import com.homes.zipsai.migration.domain.ShadowReadPhase;
import com.homes.zipsai.migration.domain.ShadowReadQuery;
import com.homes.zipsai.migration.domain.ShadowReadRequest;
import com.homes.zipsai.migration.domain.ShadowReadResult;
import com.homes.zipsai.migration.domain.ShadowReadRowCheck;
import com.homes.zipsai.migration.domain.WorkerMode;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.PropertyNamingStrategies;

@Component
public class MigrationEventLogger {

    private static final Logger LOGGER = LoggerFactory.getLogger("structured-events");
    private static final String SERVICE = "backend";
    private static final int ERROR_MAX_LENGTH = 500;

    private final ObjectMapper objectMapper;

    public MigrationEventLogger(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper.rebuild()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .build();
    }

    public void outboxApplied(OutboxEvent event, long durationMs, long lagMs) {
        log("outbox_applied", "INFO", OutboxLog.of(event, "success", event.retryCount())
            .withTimes(durationMs, lagMs));
    }

    public void outboxRetry(OutboxEvent event, int retryCount, String error) {
        log("outbox_retry", "WARN", OutboxLog.of(event, "retry", retryCount).withError(error));
    }

    public void outboxFailed(OutboxEvent event, int retryCount, String error) {
        log("outbox_failed", "ERROR", OutboxLog.of(event, "failure", retryCount).withError(error));
    }

    public void readServed(String traceId, String method, ShadowReadQuery query) {
        log("migration_read", "INFO", new ReadLog(traceId, method, query.database(), query.startedAt(),
            query.finishedAt()));
    }

    public void shadowReadMismatch(ShadowReadRequest request, ShadowReadPhase phase, ShadowReadResult result,
                                   ShadowReadComparison comparison) {
        String recheck = phase == ShadowReadPhase.RECOMPARE ? "full_result" : null;
        String level = result == ShadowReadResult.MISMATCH_RESOLVED ? "INFO" : "WARN";
        log("shadow_read_mismatch", level, ComparisonLog.of(ShadowReadContext.of(request, phase, result), recheck,
            comparison));
    }

    public void shadowReadRowChecked(ShadowReadRequest request, ShadowReadRowCheck rowCheck) {
        ShadowReadResult result = rowCheck.result();
        String level = result == ShadowReadResult.MISMATCH_PERSISTENT ? "WARN" : "INFO";
        log("shadow_read_mismatch", level, RowCheckLog.of(
            ShadowReadContext.of(request, ShadowReadPhase.RECOMPARE, result), rowCheck));
    }

    public void shadowReadError(ShadowReadRequest request, ShadowReadPhase phase, Throwable throwable) {
        String error = TextUtils.truncate(String.valueOf(throwable), ERROR_MAX_LENGTH);
        log("shadow_read_error", "WARN", new FailureLog(
            ShadowReadContext.of(request, phase, ShadowReadResult.ERROR), error, null));
    }

    public void shadowReadSkipped(ShadowReadRequest request, ShadowReadPhase phase) {
        log("shadow_read_skipped", "WARN", new FailureLog(
            ShadowReadContext.of(request, phase, ShadowReadResult.SKIPPED), null, "queue_full"));
    }

    public void settingsChanged(MigrationSettings settings) {
        log("migration_settings_changed", "INFO", SettingsLog.from(settings));
    }

    private void log(String event, String level, Object fields) {
        try {
            LOGGER.info(objectMapper.writeValueAsString(new LogLine(Instant.now(), level, SERVICE, event, fields)));
        } catch (JacksonException exception) {
            LOGGER.info("structured_event_serialization_failed event={}", event);
        }
    }

    private record LogLine(Instant timestamp, String level, String service, String event,
                           @JsonUnwrapped Object fields) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record OutboxLog(Long eventId, OutboxDirection direction, String entityType, String entityId,
                             String outcome, int retryCount, Long durationMs, Long lagMs, String error) {

        static OutboxLog of(OutboxEvent event, String outcome, int retryCount) {
            return new OutboxLog(event.eventId(), event.direction(), event.entityType(), event.entityId(), outcome,
                retryCount, null, null, null);
        }

        OutboxLog withTimes(long durationMs, long lagMs) {
            return new OutboxLog(eventId, direction, entityType, entityId, outcome, retryCount, durationMs, lagMs,
                null);
        }

        OutboxLog withError(String error) {
            return new OutboxLog(eventId, direction, entityType, entityId, outcome, retryCount, null, null, error);
        }
    }

    private record ReadLog(String traceId, String method, MigrationDatabase responseDatabase, Instant startedAt,
                           Instant finishedAt) {
    }

    private record ShadowReadContext(String traceId, String method, String phase, MigrationDatabase responseDatabase,
                                     String outcome) {

        static ShadowReadContext of(ShadowReadRequest request, ShadowReadPhase phase, ShadowReadResult result) {
            return new ShadowReadContext(request.traceId(), request.method(), phase.getTag(),
                request.responseDatabase(), result.getTag());
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record ComparisonLog(@JsonUnwrapped ShadowReadContext context, String recheck, Instant v1StartedAt,
                                 Instant v1FinishedAt, Instant v2StartedAt, Instant v2FinishedAt,
                                 List<String> onlyInV1Ids, int onlyInV1Count, List<String> onlyInV2Ids,
                                 int onlyInV2Count, boolean orderDiffers, List<FieldDifference> fieldDifferences,
                                 int fieldDifferenceCount) {

        static ComparisonLog of(ShadowReadContext context, String recheck, ShadowReadComparison comparison) {
            return new ComparisonLog(context, recheck, comparison.v1Query().startedAt(),
                comparison.v1Query().finishedAt(), comparison.v2Query().startedAt(),
                comparison.v2Query().finishedAt(), comparison.onlyInV1Ids(), comparison.onlyInV1Count(),
                comparison.onlyInV2Ids(), comparison.onlyInV2Count(), comparison.orderDiffers(),
                comparison.fieldDifferences(), comparison.fieldDifferenceCount());
        }
    }

    private record RowCheckLog(@JsonUnwrapped ShadowReadContext context, String recheck, String table,
                               Instant rowsStartedAt, Instant rowsFinishedAt, List<String> resolvedIds,
                               List<String> laggingIds, List<String> persistentIds,
                               List<FieldDifference> columnDifferences, int columnDifferenceCount) {

        static RowCheckLog of(ShadowReadContext context, ShadowReadRowCheck rowCheck) {
            return new RowCheckLog(context, "primary_key", rowCheck.tableName(), rowCheck.readStartedAt(),
                rowCheck.readFinishedAt(), rowCheck.resolvedIds(), rowCheck.laggingIds(), rowCheck.persistentIds(),
                rowCheck.columnDifferences(), rowCheck.columnDifferenceCount());
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record FailureLog(@JsonUnwrapped ShadowReadContext context, String error, String reason) {
    }

    private record SettingsLog(MigrationMode mode, String switchAt, WorkerMode workerMode,
                               boolean forwardWorkerEnabled, boolean reverseWorkerEnabled, int v2ReadPercent,
                               int shadowReadPercent) {

        static SettingsLog from(MigrationSettings settings) {
            String switchAt = settings.switchAt() == null ? null : settings.switchAt().toString();
            return new SettingsLog(settings.mode(), switchAt, settings.workerMode(),
                settings.forwardWorkerEnabled(), settings.reverseWorkerEnabled(), settings.v2ReadPercent(),
                settings.shadowReadPercent());
        }
    }
}
