package com.homes.zipsai.global.exception;

import java.util.Map;

public class ApiException extends RuntimeException {
    public final int status;
    public final String code;
    public final Map<String, ?> details;
    public ApiException(int status, String code, String message, Map<String, ?> details) {
        super(message); this.status = status; this.code = code; this.details = details;
    }
    public static ApiException unauthorized() {
        return new ApiException(401, "UNAUTHORIZED", "인증이 필요합니다.", Map.of("reason", "유효한 인증 토큰이 필요합니다."));
    }
    public static ApiException forbidden() {
        return new ApiException(403, "FORBIDDEN", "접근 권한이 없습니다.", Map.of());
    }
}
