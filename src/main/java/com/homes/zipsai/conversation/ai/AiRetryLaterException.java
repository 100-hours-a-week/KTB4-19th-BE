package com.homes.zipsai.conversation.ai;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

import org.springframework.http.HttpHeaders;

import com.homes.zipsai.global.exception.AiUnavailableException;

public class AiRetryLaterException extends AiUnavailableException {

    private final Duration retryAfter;
    private final boolean inProgress;

    public AiRetryLaterException(Duration retryAfter) {
        this(retryAfter, false);
    }

    public AiRetryLaterException(Duration retryAfter, boolean inProgress) {
        this.retryAfter = retryAfter;
        this.inProgress = inProgress;
    }

    public Duration retryAfter() {
        return retryAfter;
    }

    public boolean inProgress() {
        return inProgress;
    }

    public static AiRetryLaterException from(HttpHeaders headers) {
        return new AiRetryLaterException(parseRetryAfter(headers.getFirst(HttpHeaders.RETRY_AFTER)));
    }

    public static AiRetryLaterException inProgress(HttpHeaders headers) {
        return new AiRetryLaterException(parseRetryAfter(headers.getFirst(HttpHeaders.RETRY_AFTER)), true);
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
