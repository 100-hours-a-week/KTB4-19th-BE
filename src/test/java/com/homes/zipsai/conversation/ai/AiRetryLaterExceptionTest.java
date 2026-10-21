package com.homes.zipsai.conversation.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AiRetryLaterExceptionTest {

    @Test
    @DisplayName("Retry-After 초 단위 값을 읽는다")
    void parsesSeconds() {
        assertThat(AiRetryLaterException.parseRetryAfter("120")).isEqualTo(Duration.ofSeconds(120));
    }

    @Test
    @DisplayName("Retry-After HTTP 날짜 값을 지금부터 남은 시간으로 바꾼다")
    void parsesHttpDate() {
        String inOneMinute = ZonedDateTime.now().plusSeconds(60).format(DateTimeFormatter.RFC_1123_DATE_TIME);

        Duration parsed = AiRetryLaterException.parseRetryAfter(inOneMinute);

        assertThat(parsed).isBetween(Duration.ofSeconds(55), Duration.ofSeconds(60));
    }

    @Test
    @DisplayName("Retry-After가 없거나 읽을 수 없으면 기다릴 시간이 없다")
    void ignoresMissingOrInvalidValue() {
        assertThat(AiRetryLaterException.parseRetryAfter(null)).isNull();
        assertThat(AiRetryLaterException.parseRetryAfter("soon")).isNull();
        assertThat(AiRetryLaterException.parseRetryAfter("-1")).isNull();
    }
}
