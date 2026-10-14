package com.homes.zipsai.common.event;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.homes.zipsai.global.exception.InvalidPubSubFieldException;
import com.homes.zipsai.global.exception.InvalidPubSubFieldException.Reason;

public record RedisMessage<T>(@JsonProperty("trace_id") String traceId, T payload) {

    public RedisMessage {
        if (payload == null) {
            throw new InvalidPubSubFieldException("payload", Reason.REQUIRED_VALUE);
        }
    }
}
