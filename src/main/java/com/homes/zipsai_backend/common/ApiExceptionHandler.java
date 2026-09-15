package com.homes.zipsai_backend.common;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<?> handle(ApiException e) {
        return ResponseEntity.status(e.status).body(ApiResponse.error(e));
    }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<?> malformed() {
        return handle(new ApiException(400, "MISSING_REQUIRED_FIELD", "요청 형식이 올바르지 않습니다.", Map.of()));
    }
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<?> forbidden() { return handle(ApiException.forbidden()); }
    @ExceptionHandler(org.springframework.security.core.AuthenticationException.class)
    public ResponseEntity<?> unauthorized() { return handle(ApiException.unauthorized()); }
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public ResponseEntity<?> notFound() {
        return handle(new ApiException(404, "RESOURCE_NOT_FOUND", "요청한 리소스를 찾을 수 없습니다.", Map.of()));
    }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> unexpected(Exception e) {
        log.error("Unhandled API failure: {}", e.getClass().getName());
        return handle(new ApiException(500, "INTERNAL_SERVER_ERROR", "서버 오류가 발생했습니다.", Map.of()));
    }
}
