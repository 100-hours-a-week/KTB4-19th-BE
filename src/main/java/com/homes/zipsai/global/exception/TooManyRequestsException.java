package com.homes.zipsai.global.exception;

import java.util.Map;

import org.springframework.http.HttpStatus;

public class TooManyRequestsException extends ApiException {

    public TooManyRequestsException() {
        super(
                HttpStatus.TOO_MANY_REQUESTS,
                "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.",
                "TOO_MANY_REQUESTS",
                Map.of("retryAfterSeconds", 30));
    }
}
