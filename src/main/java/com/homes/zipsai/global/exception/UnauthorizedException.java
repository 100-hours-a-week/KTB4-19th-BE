package com.homes.zipsai.global.exception;

import java.util.Map;

import org.springframework.http.HttpStatus;

public class UnauthorizedException extends ApiException {

    public UnauthorizedException() {
        this("인증이 필요합니다.", "UNAUTHORIZED", Map.of("reason", "유효한 인증 토큰이 필요합니다."));
    }

    protected UnauthorizedException(String message, String code, Map<String, ?> details) {
        super(HttpStatus.UNAUTHORIZED, message, code, details);
    }
}
