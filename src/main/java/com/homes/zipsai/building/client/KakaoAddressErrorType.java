package com.homes.zipsai.building.client;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum KakaoAddressErrorType {
    ADDRESS_NOT_FOUND(RetryPolicy.NONE, false),
    AMBIGUOUS_ADDRESS(RetryPolicy.NONE, false),
    NON_BUILDING_ADDRESS(RetryPolicy.NONE, false),
    KAKAO_SERVER_ERROR(RetryPolicy.LIMITED, true),
    NETWORK_ERROR(RetryPolicy.LIMITED, true),
    AUTHENTICATION_ERROR(RetryPolicy.UNLIMITED, true),
    PERMISSION_ERROR(RetryPolicy.UNLIMITED, true),
    APP_STATE_ERROR(RetryPolicy.UNLIMITED, true),
    QUOTA_EXCEEDED(RetryPolicy.UNLIMITED, true),
    REQUEST_CONFIGURATION_ERROR(RetryPolicy.UNLIMITED, true),
    INVALID_RESPONSE(RetryPolicy.UNLIMITED, true),
    UNKNOWN_HTTP_ERROR(RetryPolicy.NONE, true),
    UNKNOWN_ERROR(RetryPolicy.NONE, false);

    private final RetryPolicy retryPolicy;
    private final boolean stopsRound;

    public enum RetryPolicy {
        NONE, LIMITED, UNLIMITED
    }
}
