package com.homes.zipsai.global.exception;

import java.util.Map;

import org.springframework.http.HttpStatus;

public class InternalServerException extends ApiException {

    public InternalServerException() {
        super(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다.", "INTERNAL_SERVER_ERROR", Map.of());
    }
}
