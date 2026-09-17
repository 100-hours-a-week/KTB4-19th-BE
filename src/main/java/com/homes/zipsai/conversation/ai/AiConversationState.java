package com.homes.zipsai.conversation.ai;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum AiConversationState {
    @JsonProperty("collecting") COLLECTING,
    @JsonProperty("action_selection") ACTION_SELECTION,
    @JsonProperty("guiding") GUIDING,
    @JsonProperty("ready_to_confirm") READY_TO_CONFIRM,
    @JsonProperty("clarifying") CLARIFYING
}
