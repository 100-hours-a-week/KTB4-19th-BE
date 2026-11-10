package com.homes.zipsai.conversation.ai.error;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum AiConverseErrorType {
    TIMEOUT(RetryPolicy.NONE),
    CONNECTION_FAILURE(RetryPolicy.NONE),
    SERVICE_UNAVAILABLE(RetryPolicy.NONE),
    GATEWAY_TIMEOUT(RetryPolicy.NONE),
    HTTP_ERROR(RetryPolicy.LIMITED),
    STREAM_ERROR(RetryPolicy.LIMITED),
    INCOMPLETE_STREAM(RetryPolicy.LIMITED),
    RESPONSE_JSON_ERROR(RetryPolicy.LIMITED),
    TURN_ID_MISMATCH(RetryPolicy.LIMITED),
    INVALID_RESPONSE(RetryPolicy.LIMITED),
    REQUEST_SERIALIZATION_ERROR(RetryPolicy.UNLIMITED);

    private final RetryPolicy retryPolicy;

    public enum RetryPolicy {
        NONE, LIMITED, UNLIMITED;

        private static final int MAX_FAILURE_COUNT = 3;

        public boolean shouldRetry(int failureCount) {
            return switch (this) {
                case NONE -> false;
                case LIMITED -> failureCount < MAX_FAILURE_COUNT;
                case UNLIMITED -> true;
            };
        }
    }
}
