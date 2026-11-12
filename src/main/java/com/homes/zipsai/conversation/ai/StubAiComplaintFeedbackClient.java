package com.homes.zipsai.conversation.ai;

import java.time.OffsetDateTime;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.ai.client", havingValue = "stub")
public class StubAiComplaintFeedbackClient implements AiComplaintFeedbackClient {
    @Override
    public AiComplaintFeedbackResponse send(AiComplaintFeedbackBatchRequest request) {
        return new AiComplaintFeedbackResponse(request.traceId(), request.items().stream()
                .map(item -> succeeded(item.complaintDetailId(), item.updatedAt()))
                .toList());
    }

    @Override
    public AiComplaintFeedbackResponse delete(AiComplaintFeedbackDeleteRequest request) {
        return new AiComplaintFeedbackResponse(request.traceId(), request.items().stream()
                .map(item -> succeeded(item.complaintDetailId(), item.updatedAt()))
                .toList());
    }

    private static AiComplaintFeedbackResponse.Result succeeded(long complaintDetailId, OffsetDateTime updatedAt) {
        return new AiComplaintFeedbackResponse.Result(complaintDetailId, updatedAt,
                AiComplaintFeedbackResponse.STATUS_SUCCEEDED, null);
    }
}
