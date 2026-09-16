package com.homes.zipsai.global.exception;

import java.util.Map;

import org.springframework.http.HttpStatus;

public class ForbiddenException extends ApiException {

    public ForbiddenException() {
        super(
                HttpStatus.FORBIDDEN,
                "해당 리소스에 접근할 권한이 없습니다.",
                "FORBIDDEN",
                Map.of("reason", "요청한 리소스에 대한 접근 권한이 없습니다."));
    }
}
