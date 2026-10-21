package com.homes.zipsai.conversation.ai;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

import org.springframework.http.HttpHeaders;

import com.homes.zipsai.global.exception.AiUnavailableException;

public class AiRetryLaterException extends AiUnavailableException {

    private final Duration retryAfter;

    public AiRetryLaterException(Duration retryAfter) {
        this.retryAfter = retryAfter;
    }

    public Duration retryAfter() {
        return retryAfter;
    }

    public static AiRetryLaterException from(HttpHeaders headers) {
        return new AiRetryLaterException(parseRetryAfter(headers.getFirst(HttpHeaders.RETRY_AFTER)));
    }

    static Duration parseRetryAfter(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        try {
            long seconds = Long.parseLong(trimmed);
            return seconds < 0 ? null : Duration.ofSeconds(seconds);
        } catch (NumberFormatException ignored) {
            // HTTP-date 형식이면 아래에서 처리한다
        }
        try {
            Duration untilThen = Duration.between(ZonedDateTime.now(),
                    ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME));
            return untilThen.isNegative() ? Duration.ZERO : untilThen;
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }
}
