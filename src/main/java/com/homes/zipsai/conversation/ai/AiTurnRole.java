package com.homes.zipsai.conversation.ai;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum AiTurnRole {
    @JsonProperty("user") USER,
    @JsonProperty("assistant") ASSISTANT
}
