package com.homes.zipsai.global.exception;

import java.util.Map;

import org.springframework.http.HttpStatus;

public abstract class UnprocessableEntityException extends ApiException {

    protected UnprocessableEntityException(String message, String code, Map<String, ?> details) {
        super(HttpStatus.UNPROCESSABLE_CONTENT, message, code, details);
    }
}
