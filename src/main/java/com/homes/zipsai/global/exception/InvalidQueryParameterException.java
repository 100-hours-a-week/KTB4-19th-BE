package com.homes.zipsai.global.exception;

import java.util.List;
import java.util.Map;

public class InvalidQueryParameterException extends BadRequestException {

    public static InvalidQueryParameterException typeMismatch(String field, Class<?> requiredType) {
        String reason;
        if (requiredType == int.class
                || requiredType == Integer.class
                || requiredType == long.class
                || requiredType == Long.class) {
            reason = "정수 형식이어야 합니다.";
        } else if ("sort".equals(field)) {
            reason = "허용되지 않은 정렬 조건입니다.";
        } else if ("status".equals(field)) {
            reason = "허용되지 않은 상태값입니다.";
        } else if (requiredType != null && requiredType.isEnum()) {
            reason = "허용되지 않은 값입니다.";
        } else {
            reason = "요청값 형식이 올바르지 않습니다.";
        }
        return new InvalidQueryParameterException(field, reason);
    }

    private InvalidQueryParameterException(String field, String reason) {
        this(List.of(Map.of("field", field, "reason", reason)));
    }

    InvalidQueryParameterException(List<Map<String, String>> violations) {
        super(
                "요청 형식이 올바르지 않습니다.",
                "INVALID_QUERY_PARAMETER",
                Map.of("violations", violations));
    }
}
