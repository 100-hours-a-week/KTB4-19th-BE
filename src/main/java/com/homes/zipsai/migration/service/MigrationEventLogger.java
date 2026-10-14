package com.homes.zipsai.migration.service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.homes.zipsai.global.util.TextUtils;
import com.homes.zipsai.migration.domain.MigrationSettings;
import com.homes.zipsai.migration.domain.OutboxEvent;
import com.homes.zipsai.migration.domain.ShadowReadComparison;
import com.homes.zipsai.migration.domain.ShadowReadComparison.FieldDifference;
import com.homes.zipsai.migration.domain.ShadowReadPhase;
import com.homes.zipsai.migration.domain.ShadowReadQuery;
import com.homes.zipsai.migration.domain.ShadowReadRequest;
import com.homes.zipsai.migration.domain.ShadowReadResult;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
public class MigrationEventLogger {

    private static final Logger LOGGER = LoggerFactory.getLogger("structured-events");
    private static final int ERROR_MAX_LENGTH = 500;

    private final ObjectMapper objectMapper;

    public MigrationEventLogger(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void outboxApplied(OutboxEvent event, long durationMs, long lagMs) {
        Map<String, Object> fields = outboxFields(event, "success", event.retryCount());
        fields.put("duration_ms", durationMs);
        fields.put("lag_ms", lagMs);
        log("outbox_applied", "INFO", fields);
    }

    public void outboxRetry(OutboxEvent event, int retryCount, String error) {
        Map<String, Object> fields = outboxFields(event, "retry", retryCount);
        fields.put("error", error);
        log("outbox_retry", "WARN", fields);
    }

    public void outboxFailed(OutboxEvent event, int retryCount, String error) {
        Map<String, Object> fields = outboxFields(event, "failure", retryCount);
        fields.put("error", error);
        log("outbox_failed", "ERROR", fields);
    }

    public void readServed(String traceId, String method, ShadowReadQuery query) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("trace_id", traceId);
        fields.put("method", method);
        fields.put("response_database", query.database());
        fields.put("started_at", query.startedAt().toString());
        fields.put("finished_at", query.finishedAt().toString());
        log("migration_read", "INFO", fields);
    }

    public void shadowReadMismatch(ShadowReadRequest request, ShadowReadPhase phase, ShadowReadResult result,
                                   ShadowReadComparison comparison) {
        Map<String, Object> fields = shadowReadFields(request, phase, result);
        putQueryTimes(fields, "v1", comparison.v1Query());
        putQueryTimes(fields, "v2", comparison.v2Query());
        fields.put("only_in_v1_ids", comparison.onlyInV1Ids());
        fields.put("only_in_v1_count", comparison.onlyInV1Count());
        fields.put("only_in_v2_ids", comparison.onlyInV2Ids());
        fields.put("only_in_v2_count", comparison.onlyInV2Count());
        fields.put("order_differs", comparison.orderDiffers());
        fields.put("field_differences", comparison.fieldDifferences().stream()
            .map(MigrationEventLogger::fieldDifferenceFields)
            .toList());
        fields.put("field_difference_count", comparison.fieldDifferenceCount());
        log("shadow_read_mismatch", result == ShadowReadResult.MISMATCH_RESOLVED ? "INFO" : "WARN", fields);
    }

    public void shadowReadError(ShadowReadRequest request, ShadowReadPhase phase, Throwable throwable) {
        Map<String, Object> fields = shadowReadFields(request, phase, ShadowReadResult.ERROR);
        fields.put("error", TextUtils.truncate(String.valueOf(throwable), ERROR_MAX_LENGTH));
        log("shadow_read_error", "WARN", fields);
    }

    public void shadowReadSkipped(ShadowReadRequest request, ShadowReadPhase phase) {
        Map<String, Object> fields = shadowReadFields(request, phase, ShadowReadResult.SKIPPED);
        fields.put("reason", "queue_full");
        log("shadow_read_skipped", "WARN", fields);
    }

    public void settingsChanged(MigrationSettings settings) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("mode", settings.mode());
        fields.put("switch_at", settings.switchAt() == null ? null : settings.switchAt().toString());
        fields.put("worker_mode", settings.workerMode());
        fields.put("forward_worker_enabled", settings.forwardWorkerEnabled());
        fields.put("reverse_worker_enabled", settings.reverseWorkerEnabled());
        fields.put("v2_read_percent", settings.v2ReadPercent());
        fields.put("shadow_read_percent", settings.shadowReadPercent());
        log("migration_settings_changed", "INFO", fields);
    }

    private static Map<String, Object> shadowReadFields(ShadowReadRequest request, ShadowReadPhase phase,
                                                        ShadowReadResult result) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("trace_id", request.traceId());
        fields.put("method", request.method());
        fields.put("phase", phase.getTag());
        fields.put("response_database", request.responseDatabase());
        fields.put("outcome", result.getTag());
        return fields;
    }

    private static void putQueryTimes(Map<String, Object> fields, String prefix, ShadowReadQuery query) {
        fields.put(prefix + "_started_at", query.startedAt().toString());
        fields.put(prefix + "_finished_at", query.finishedAt().toString());
    }

    private static Map<String, Object> fieldDifferenceFields(FieldDifference difference) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("id", difference.id());
        fields.put("field", difference.field());
        fields.put("v1", difference.v1Value());
        fields.put("v2", difference.v2Value());
        return fields;
    }

    private Map<String, Object> outboxFields(OutboxEvent event, String outcome, int retryCount) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("event_id", event.eventId());
        fields.put("direction", event.direction());
        fields.put("entity_type", event.entityType());
        fields.put("entity_id", event.entityId());
        fields.put("outcome", outcome);
        fields.put("retry_count", retryCount);
        return fields;
    }

    private void log(String eventName, String level, Map<String, Object> fields) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("timestamp", Instant.now().toString());
        event.put("level", level);
        event.put("service", "backend");
        event.put("event", eventName);
        event.putAll(fields);
        try {
            LOGGER.info(objectMapper.writeValueAsString(event));
        } catch (JacksonException exception) {
            LOGGER.info("structured_event_serialization_failed event={}", eventName);
        }
    }
}
