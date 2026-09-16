package com.homes.zipsai.global.exception;

import java.util.List;
import java.util.Map;

public class MissingFieldException extends BadRequestException {

    public MissingFieldException(String field) {
        this(List.of(Map.of("field", field, "reason", "필수 입력값입니다.")));
    }

    MissingFieldException(List<Map<String, String>> violations) {
        super(
                "요청 형식이 올바르지 않습니다.",
                "MISSING_REQUIRED_FIELD",
                Map.of("violations", violations));
    }
}
