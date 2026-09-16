package com.homes.zipsai.global.exception;

import java.util.Map;

import org.springframework.http.HttpStatus;

public abstract class BadRequestException extends ApiException {

    protected BadRequestException(String message, String code, Map<String, ?> details) {
        super(HttpStatus.BAD_REQUEST, message, code, details);
    }
}
