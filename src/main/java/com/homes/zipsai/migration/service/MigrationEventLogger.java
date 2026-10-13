package com.homes.zipsai.migration.service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.homes.zipsai.migration.domain.MigrationSettings;
import com.homes.zipsai.migration.domain.OutboxEvent;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
public class MigrationEventLogger {

    private static final Logger LOGGER = LoggerFactory.getLogger("structured-events");

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

    public void shadowReadMismatch(String method, String outcome) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("method", method);
        fields.put("outcome", outcome);
        log("shadow_read_mismatch", "WARN", fields);
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
