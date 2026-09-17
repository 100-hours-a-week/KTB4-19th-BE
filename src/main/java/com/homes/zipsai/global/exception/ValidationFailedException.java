package com.homes.zipsai.global.exception;

import java.util.List;
import java.util.Map;

public class ValidationFailedException extends UnprocessableEntityException {

    public enum Reason {
        UNEXPECTED_FIELD("허용되지 않은 필드입니다."),
        EXPECTED_STRING("문자열이어야 합니다."),
        INVALID_EMAIL_FORMAT("이메일 형식이 올바르지 않습니다."),
        INVALID_PASSWORD_FORMAT("비밀번호 형식이 올바르지 않습니다."),
        INVALID_USER_NAME_LENGTH("이름은 1~7자여야 합니다."),
        INVALID_PHONE_FORMAT("연락처 형식이 올바르지 않습니다."),
        INVALID_ID("1 이상의 정수여야 합니다."),
        AGREEMENTS_NOT_ARRAY("배열이어야 합니다."),
        INVALID_TERMS_TYPE("허용되지 않은 약관 타입입니다."),
        IS_AGREED_NOT_BOOLEAN("동의 여부는 boolean이어야 합니다."),
        DUPLICATE_TERMS_TYPE("약관 타입이 중복되었습니다."),
        REQUIRED_TERMS_NOT_AGREED("필수 약관은 동의해야 합니다."),
        PASSWORD_CONFIRMATION_MISMATCH("비밀번호가 일치하지 않습니다."),
        INVALID_USER_ROLE("허용되지 않은 역할입니다."),
        // 건물 등록 요청의 길이 제한을 넘겼을 때 사용하는 검증 사유입니다.
        BUILDING_NAME_TOO_LONG("건물명은 20자 이하여야 합니다."),
        ROAD_ADDRESS_TOO_LONG("주소는 200자 이하여야 합니다.");

        private final String message;

        Reason(String message) {
            this.message = message;
        }
    }

    public ValidationFailedException(String field, Reason reason) {
        this(List.of(Map.of("field", field, "reason", reason.message)));
    }

    ValidationFailedException(List<Map<String, String>> violations) {
        super(
                "입력값이 유효하지 않습니다.",
                "VALIDATION_FAILED",
                Map.of("violations", violations));
    }
}
