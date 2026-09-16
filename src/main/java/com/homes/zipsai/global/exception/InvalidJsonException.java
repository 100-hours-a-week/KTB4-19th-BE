package com.homes.zipsai.global.exception;

import java.util.List;
import java.util.Map;

public class InvalidJsonException extends BadRequestException {

    public InvalidJsonException() {
        super(
                "요청 형식이 올바르지 않습니다.",
                "MISSING_REQUIRED_FIELD",
                Map.of(
                        "violations",
                        List.of(Map.of("field", "body", "reason", "요청 본문 형식이 올바르지 않습니다."))));
    }
}
