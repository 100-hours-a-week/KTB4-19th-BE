package com.homes.zipsai.global.exception;

import java.util.Map;

public class InvalidCredentialsException extends UnauthorizedException {

    public InvalidCredentialsException() {
        super(
                "인증이 필요합니다.",
                "UNAUTHORIZED",
                Map.of("reason", "이메일 또는 비밀번호가 일치하지 않습니다."));
    }
}
