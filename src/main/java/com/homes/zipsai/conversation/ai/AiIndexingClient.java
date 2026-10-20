package com.homes.zipsai.conversation.ai;

import java.util.List;

public interface AiIndexingClient {

    AiIndexingResponse index(AiIndexingRequest request);

    default AiIndexingResponse cleanup(Long buildingId, String traceId, List<String> validDocumentIds) {
        return index(new AiIndexingRequest(buildingId, traceId, null, null, null, validDocumentIds));
    }
}
