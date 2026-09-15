package com.homes.zipsai.user.validator;

import java.util.*;
import java.util.regex.Pattern;

import com.homes.zipsai.global.exception.ApiException;
import com.homes.zipsai.user.domain.TermsType;

import tools.jackson.databind.JsonNode;

public final class UserInput {
    private static final Pattern EMAIL = Pattern.compile("^[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+(?:\\.[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+)*@[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)+$");
    private UserInput() {}
    public static ApiException invalid(String field, String reason) {
        return new ApiException(422, "VALIDATION_FAILED", "입력값이 유효하지 않습니다.", Map.of("violations", List.of(Map.of("field", field, "reason", reason))));
    }
    public static ApiException missing(String field) {
        return new ApiException(400, "MISSING_REQUIRED_FIELD", "요청 형식이 올바르지 않습니다.", Map.of("violations", List.of(Map.of("field", field, "reason", "필수 입력값입니다."))));
    }
    public static void fields(JsonNode body, Set<String> allowed) {
        if (body == null || !body.isObject() || body.isEmpty()) throw missing("body");
        for (String field : body.propertyNames()) if (!allowed.contains(field)) throw invalid(field, "허용되지 않은 필드입니다.");
    }
    public static String text(JsonNode body, String field, boolean required) {
        JsonNode value = body.get(field);
        if (value == null || value.isNull()) { if (required) throw missing(field); return null; }
        if (!value.isTextual()) throw invalid(field, "문자열이어야 합니다.");
        if (required && value.asText().isBlank()) throw missing(field);
        return value.asText();
    }
    public static String email(String value) {
        if (value == null || value.isBlank()) throw missing("email");
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() > 254 || !EMAIL.matcher(normalized).matches()) throw invalid("email", "이메일 형식이 올바르지 않습니다.");
        return normalized;
    }
    public static String password(String value) {
        if (value == null || value.isBlank()) throw missing("password");
        if (!value.matches("(?=.*[A-Za-z])(?=.*[0-9])(?=.*[^A-Za-z0-9])[!-~]{8,20}"))
            throw invalid("password", "비밀번호 형식이 올바르지 않습니다.");
        return value;
    }
    public static String name(String value) {
        if (value == null) return null;
        value = value.trim();
        if (value.isEmpty() || value.length() > 7) throw invalid("userName", "이름은 1~7자여야 합니다.");
        return value;
    }
    public static String phone(String value) {
        if (value == null || value.isBlank()) return null;
        if (!value.matches("[0-9]{10,11}|[0-9]{3}-[0-9]{3,4}-[0-9]{4}")) throw invalid("phone", "연락처 형식이 올바르지 않습니다.");
        String digits = value.replace("-", "");
        return digits.substring(0, 3) + "-" + digits.substring(3, digits.length()-4) + "-" + digits.substring(digits.length()-4);
    }
    public static Map<TermsType, Boolean> agreements(JsonNode body, boolean signup) {
        JsonNode array = body.get("agreements");
        if (array == null || array.isNull()) { if (signup || body.has("agreements")) throw missing("agreements"); return Map.of(); }
        if (!array.isArray()) throw invalid("agreements", "배열이어야 합니다.");
        Map<TermsType, Boolean> result = new EnumMap<>(TermsType.class);
        for (JsonNode item : array) {
            fields(item, Set.of("termsType", "isAgreed"));
            TermsType type;
            try { type = TermsType.valueOf(text(item, "termsType", true)); }
            catch (IllegalArgumentException e) { throw invalid("termsType", "허용되지 않은 약관 타입입니다."); }
            JsonNode agreed = item.get("isAgreed");
            if (agreed == null || !agreed.isBoolean()) throw invalid("isAgreed", "동의 여부는 boolean이어야 합니다.");
            if (result.put(type, agreed.asBoolean()) != null) throw invalid("agreements", "약관 타입이 중복되었습니다.");
            if (type != TermsType.MARKETING && !agreed.asBoolean()) throw invalid("agreements", "필수 약관은 동의해야 합니다.");
        }
        if (signup && (!Boolean.TRUE.equals(result.get(TermsType.SERVICE)) || !Boolean.TRUE.equals(result.get(TermsType.PRIVACY))))
            throw invalid("agreements", "필수 약관은 동의해야 합니다.");
        if (signup) result.putIfAbsent(TermsType.MARKETING, false);
        return result;
    }
}
