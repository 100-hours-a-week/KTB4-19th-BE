package com.homes.zipsai.global.logging;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
public class StructuredLogger {
    private static final Logger LOGGER = LoggerFactory.getLogger("structured-events");
    private final ObjectMapper objectMapper;

    public StructuredLogger(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void requestStarted(String traceId, String route, String method) {
        log(event("request_started", traceId, route, Map.of("method", method)));
    }

    public void stageDone(String traceId, String route, String stage, long durationMs, String outcome) {
        if ("mysql".equals(stage)) {
            MDC.put("dbMs", Long.toString(durationMs));
        }
        if ("ai_api".equals(stage)) {
            MDC.put("aiApiMs", Long.toString(durationMs));
        }
        log(event("stage_done", traceId, route,
            Map.of("stage", stage, "duration_ms", durationMs, "outcome", outcome)));
    }

    public void requestDone(String traceId, String route, String method, int statusCode,
                            long totalMs, String errorCode) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("method", method); fields.put("status_code", statusCode);
        fields.put("outcome", statusCode >= 400 ? "error" : "success");
        fields.put("total_ms", totalMs);
        fields.put("db_ms", numberOrNull(MDC.get("dbMs")));
        fields.put("ai_api_ms", numberOrNull(MDC.get("aiApiMs")));
        fields.put("error_code", errorCode);
        log(event("request_done", traceId, route, fields));
    }

    private Object numberOrNull(String value) {
        return value == null ? null : Long.valueOf(value);
    }

    private Map<String, Object> event(String name, String traceId, String route, Map<String, Object> fields) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("timestamp", Instant.now().toString()); event.put("level", "INFO");
        event.put("service", "backend"); event.put("event", name);
        event.put("trace_id", traceId); event.put("route", route); event.putAll(fields);
        return event;
    }

    private void log(Map<String, Object> event) {
        try {
            LOGGER.info(objectMapper.writeValueAsString(event));
        } catch (JacksonException ignored) {
            LOGGER.info("structured_event_serialization_failed");
        }
    }
}
