package com.homes.zipsai.conversation.ai;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum AiRoute {
    @JsonProperty("complaint") COMPLAINT,
    @JsonProperty("knowledge") KNOWLEDGE,
    @JsonProperty("clarify") CLARIFY
}
