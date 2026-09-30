package com.homes.zipsai.global.logging;

import java.time.Instant;
import java.util.Objects;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.NestedExceptionUtils;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;
import tools.jackson.databind.json.JsonMapper;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ServerErrorLog(
    String timestamp,
    String level,
    String service,
    String event,
    String traceId,
    String route,
    String method,
    int statusCode,
    String errorCode,
    String exceptionType,
    String errorMessage,
    String rootCauseType,
    String rootCauseMessage
) {
    private static final Logger LOGGER = LoggerFactory.getLogger("structured-events");
    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder().build();
    private static final int MAX_MESSAGE_LENGTH = 2000;

    public static ServerErrorLog of(HttpServletRequest request, Throwable exception) {
        Throwable rootCause = NestedExceptionUtils.getMostSpecificCause(exception);
        return new ServerErrorLog(
            Instant.now().toString(),
            "ERROR",
            "backend",
            "request_error",
            MDC.get("traceId"),
            request.getRequestURI(),
            request.getMethod(),
            500,
            Objects.requireNonNullElse(MDC.get(StructuredLogger.ERROR_CODE), "INTERNAL_SERVER_ERROR"),
            exception.getClass().getName(),
            mask(exception.getMessage()),
            rootCause.getClass().getName(),
            mask(rootCause.getMessage())
        );
    }

    public void print() {
        LOGGER.info(OBJECT_MAPPER.writeValueAsString(this));
    }

    private static String mask(String message) {
        if (message == null) {
            return null;
        }
        String masked = message
            .replaceAll("[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+", "***")
            .replaceAll("'[^']*'|\"[^\"]*\"", "'?'")
            .replaceAll("\\s+", " ")
            .strip();
        return masked.length() <= MAX_MESSAGE_LENGTH ? masked : masked.substring(0, MAX_MESSAGE_LENGTH);
    }
}
