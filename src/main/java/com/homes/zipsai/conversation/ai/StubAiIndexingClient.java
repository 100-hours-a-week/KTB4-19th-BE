package com.homes.zipsai.conversation.ai;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.ai.client", havingValue = "stub")
public class StubAiIndexingClient implements AiIndexingClient {
    @Override
    public void index(AiIndexingRequest request) {
    }
}
