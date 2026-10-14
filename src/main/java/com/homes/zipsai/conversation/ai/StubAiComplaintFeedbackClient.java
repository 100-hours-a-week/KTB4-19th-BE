package com.homes.zipsai.conversation.ai;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.ai.client", havingValue = "stub")
public class StubAiComplaintFeedbackClient implements AiComplaintFeedbackClient {
    @Override
    public AiComplaintFeedbackBatchResponse send(AiComplaintFeedbackBatchRequest request) {
        return new AiComplaintFeedbackBatchResponse(request.items().stream()
                .map(item -> new AiComplaintFeedbackBatchResponse.Result(item.complaintDetailId(), "ACCEPTED", null))
                .toList());
    }
}
