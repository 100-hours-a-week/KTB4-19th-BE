package com.homes.zipsai.global.exception;

import java.util.Map;

import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {
    public final int status;
    public final String code;
    public final Map<String, ?> details;

    protected ApiException(int status, String code, String message, Map<String, ?> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details;
    }

    protected ApiException(HttpStatus status, String message, String code, Map<String, ?> details) {
        this(status.value(), code, message, details);
    }

    protected static Map<String, String> fieldReason(String field, String reason) {
        return Map.of("field", field, "reason", reason);
    }
}
