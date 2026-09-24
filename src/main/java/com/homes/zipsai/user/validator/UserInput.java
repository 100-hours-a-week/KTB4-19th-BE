package com.homes.zipsai.user.validator;

import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.homes.zipsai.auth.dto.AgreementRequest;
import com.homes.zipsai.global.exception.MissingFieldException;
import com.homes.zipsai.global.exception.ValidationFailedException;
import com.homes.zipsai.global.exception.ValidationFailedException.Reason;
import com.homes.zipsai.user.domain.TermsType;

import tools.jackson.databind.JsonNode;

public final class UserInput {
    public static final String EMAIL_PATTERN = "^(?=.{1,254}$)[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+"
                    + "(?:\\.[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+)*"
                    + "@[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?"
                    + "(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)+$";
    public static final String PASSWORD_PATTERN = "(?=.*[A-Za-z])(?=.*[0-9])(?=.*[^A-Za-z0-9])[!-~]{8,20}";
    public static final String PHONE_PATTERN =
            "[0-9]{10,11}|[0-9]{3}-[0-9]{3,4}-[0-9]{4}|\\p{javaWhitespace}*";

    private static final Pattern EMAIL = Pattern.compile(EMAIL_PATTERN);

    private UserInput() {
    }

    public static ValidationFailedException invalid(String field, Reason reason) {
        return new ValidationFailedException(field, reason);
    }

    public static MissingFieldException missing(String field) {
        return new MissingFieldException(field);
    }

    public static void fields(JsonNode body, Set<String> allowed) {
        if (body == null || !body.isObject() || body.isEmpty()) {
            throw missing("body");
        }
        for (String field : body.propertyNames()) {
            if (!allowed.contains(field)) {
                throw invalid(field, Reason.UNEXPECTED_FIELD);
            }
        }
    }

    public static String text(JsonNode body, String field, boolean required) {
        JsonNode value = body.get(field);
        if (value == null || value.isNull()) {
            if (required) {
                throw missing(field);
            }
            return null;
        }
        if (!value.isTextual()) {
            throw invalid(field, Reason.EXPECTED_STRING);
        }
        if (required && value.asText().isBlank()) {
            throw missing(field);
        }
        return value.asText();
    }

    public static String email(String value) {
        if (value == null || value.isBlank()) {
            throw missing("email");
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() > 254 || !EMAIL.matcher(normalized).matches()) {
            throw invalid("email", Reason.INVALID_EMAIL_FORMAT);
        }
        return normalized;
    }

    public static String password(String value) {
        if (value == null || value.isBlank()) {
            throw missing("password");
        }
        if (!value.matches("(?=.*[A-Za-z])(?=.*[0-9])(?=.*[^A-Za-z0-9])[!-~]{8,20}")) {
            throw invalid("password", Reason.INVALID_PASSWORD_FORMAT);
        }
        return value;
    }

    public static String name(String value) {
        if (value == null) {
            return null;
        }
        value = value.trim();
        if (value.isEmpty() || value.length() > 7) {
            throw invalid("userName", Reason.INVALID_USER_NAME_LENGTH);
        }
        return value;
    }

    public static String phone(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (!value.matches("[0-9]{10,11}|[0-9]{3}-[0-9]{3,4}-[0-9]{4}")) {
            throw invalid("phone", Reason.INVALID_PHONE_FORMAT);
        }
        String digits = value.replace("-", "");
        return digits.substring(0, 3)
                + "-"
                + digits.substring(3, digits.length() - 4)
                + "-"
                + digits.substring(digits.length() - 4);
    }

    public static Map<TermsType, Boolean> agreements(List<AgreementRequest> agreements, boolean signup) {
        if (agreements == null) {
            if (signup) {
                throw missing("agreements");
            }
            return Map.of();
        }

        Map<TermsType, Boolean> result = new EnumMap<>(TermsType.class);

        for (AgreementRequest item : agreements) {
            if (item == null) {
                throw invalid("agreements", Reason.INVALID_TERMS_TYPE);
            }

            TermsType type = item.termsType();
            if (type == null) {
                throw missing("termsType");
            }

            Boolean agreed = item.isAgreed();
            if (agreed == null) {
                throw invalid("isAgreed", Reason.IS_AGREED_NOT_BOOLEAN);
            }

            if (result.put(type, agreed) != null) {
                throw invalid("agreements", Reason.DUPLICATE_TERMS_TYPE);
            }

            if (type != TermsType.MARKETING && !agreed) {
                throw invalid("agreements", Reason.REQUIRED_TERMS_NOT_AGREED);
            }
        }

        if (signup
            && (!Boolean.TRUE.equals(result.get(TermsType.SERVICE))
            || !Boolean.TRUE.equals(result.get(TermsType.PRIVACY)))) {
            throw invalid("agreements", Reason.REQUIRED_TERMS_NOT_AGREED);
        }

        if (signup) {
            result.putIfAbsent(TermsType.MARKETING, false);
        }

        return result;
    }
}
