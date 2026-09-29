package com.homes.zipsai.conversation.ai;

import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Component
@ConditionalOnProperty(name = "app.ai.client", havingValue = "stub")
public class StubAiIndexingClient implements AiIndexingClient {
    @Override
    public void index(AiIndexingRequest request) {
    }
}
