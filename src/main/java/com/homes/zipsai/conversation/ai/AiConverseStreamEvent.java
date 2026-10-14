package com.homes.zipsai.conversation.ai;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;

public record AiConverseStreamEvent(@JsonFormat(with = JsonFormat.Feature.READ_UNKNOWN_ENUM_VALUES_AS_NULL) Type type,
                                    String field, String text, String message, AiConverseResponse response) {

    private static final String REPLY_FIELD = "reply";

    public boolean isReplyDelta() {
        return type == Type.DELTA && REPLY_FIELD.equals(field) && text != null;
    }

    public boolean isComplete() {
        return type == Type.COMPLETE && response != null;
    }

    public boolean isError() {
        return type == Type.ERROR;
    }

    public enum Type {
        @JsonProperty("delta") DELTA,
        @JsonProperty("complete") COMPLETE,
        @JsonProperty("error") ERROR
    }
}
