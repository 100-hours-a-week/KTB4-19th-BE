package com.homes.zipsai.global.exception;

public class InvalidPubSubFieldException extends IllegalArgumentException {

    public enum Reason {
        REQUIRED_VALUE("필수 입력값입니다."),
        INVALID_ID("1 이상의 정수여야 합니다.");

        private final String message;

        Reason(String message) {
            this.message = message;
        }
    }

    public final String field;
    public final Reason reason;

    public InvalidPubSubFieldException(String field, Reason reason) {
        super(field + ": " + reason.message);
        this.field = field;
        this.reason = reason;
    }
}
