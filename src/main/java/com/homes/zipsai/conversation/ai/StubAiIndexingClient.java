package com.homes.zipsai.conversation.ai;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.ai.client", havingValue = "stub")
public class StubAiIndexingClient implements AiIndexingClient {
    @Override
    public AiIndexingResponse index(AiIndexingRequest request) {
        List<AiIndexingResponse.Result> results = new ArrayList<>();
        if (request.docId() != null) {
            results.add(new AiIndexingResponse.Result(AiIndexingResponse.KIND_INDEX, request.docId(),
                    AiIndexingResponse.STATUS_SUCCEEDED, null));
        }
        results.add(new AiIndexingResponse.Result(AiIndexingResponse.KIND_CLEANUP, null,
                AiIndexingResponse.STATUS_SUCCEEDED, null));
        return new AiIndexingResponse(request.traceId(), results);
    }
}
