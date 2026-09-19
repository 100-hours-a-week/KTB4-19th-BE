package com.homes.zipsai.conversation.ai;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum AiComplaintState {
    @JsonProperty("collecting") COLLECTING,
    @JsonProperty("guiding") GUIDING,
    @JsonProperty("clarifying") CLARIFYING,
    READY_TO_CONFIRM
}
