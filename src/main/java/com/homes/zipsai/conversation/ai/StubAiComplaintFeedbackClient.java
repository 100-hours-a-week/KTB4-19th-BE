package com.homes.zipsai.conversation.ai;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.ai.client", havingValue = "stub")
public class StubAiComplaintFeedbackClient implements AiComplaintFeedbackClient {
    @Override
    public void send(AiComplaintFeedbackBatchRequest request) {
    }
}
