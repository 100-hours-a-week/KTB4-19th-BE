package com.homes.zipsai.global.exception;

import java.util.Map;

import org.springframework.http.HttpStatus;

public class AiUnavailableException extends ApiException {

    public AiUnavailableException() {
        super(
                HttpStatus.SERVICE_UNAVAILABLE,
                "AI 도우미가 일시적으로 응답하지 못했습니다. 잠시 후 다시 시도해 주세요.",
                "AI_UNAVAILABLE",
                Map.of("retryAfterSeconds", 10));
    }
}
