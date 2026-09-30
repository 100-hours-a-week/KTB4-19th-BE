package com.homes.zipsai.conversation.ai;

public interface AiIndexingClient {

    void index(AiIndexingRequest request);

    default void cleanup(Long buildingId, String traceId, java.util.List<String> validDocumentIds) {
        index(new AiIndexingRequest(buildingId, traceId, null, null, null, validDocumentIds));
    }
}
