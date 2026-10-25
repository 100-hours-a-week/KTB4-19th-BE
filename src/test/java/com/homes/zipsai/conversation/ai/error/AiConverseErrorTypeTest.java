package com.homes.zipsai.conversation.ai.error;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class AiConverseErrorTypeTest {

    @ParameterizedTest
    @CsvSource({
        "TIMEOUT, NONE",
        "CONNECTION_FAILURE, NONE",
        "SERVICE_UNAVAILABLE, NONE",
        "GATEWAY_TIMEOUT, NONE",
        "HTTP_ERROR, LIMITED",
        "STREAM_ERROR, LIMITED",
        "INCOMPLETE_STREAM, LIMITED",
        "RESPONSE_JSON_ERROR, LIMITED",
        "TURN_ID_MISMATCH, LIMITED",
        "INVALID_RESPONSE, LIMITED",
        "REQUEST_SERIALIZATION_ERROR, UNLIMITED"
    })
    @DisplayName("AI 오류 원인별로 최종 실패와 실패 횟수 증가 또는 유지 정책을 구분한다")
    void classifiesRetryPolicy(AiConverseErrorType errorType, AiConverseErrorType.RetryPolicy expected) {
        assertThat(errorType.getRetryPolicy()).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
        "NONE, 1, false",
        "LIMITED, 1, true",
        "LIMITED, 2, true",
        "LIMITED, 3, false",
        "UNLIMITED, 0, true",
        "UNLIMITED, 2, true"
    })
    @DisplayName("집계된 실패가 세 번이면 횟수 증가 정책의 재시도를 막고 횟수 유지 정책에는 적용하지 않는다")
    void checksRetryLimitAfterFailureIsCounted(AiConverseErrorType.RetryPolicy policy, int failureCount,
                                              boolean expected) {
        assertThat(policy.shouldRetry(failureCount)).isEqualTo(expected);
    }
}
