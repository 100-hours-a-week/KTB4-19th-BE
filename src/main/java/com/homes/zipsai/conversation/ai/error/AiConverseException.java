package com.homes.zipsai.conversation.ai.error;

import com.homes.zipsai.global.exception.ApiException;

import lombok.Getter;

@Getter
public class AiConverseException extends ApiException {

    private final AiConverseErrorType errorType;

    public AiConverseException(AiConverseErrorType errorType, ApiException publicError, Throwable cause) {
        super(publicError.status, publicError.code, publicError.getMessage(), publicError.details);
        this.errorType = errorType;
        initCause(cause);
    }
}
