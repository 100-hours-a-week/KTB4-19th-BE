package com.homes.zipsai.conversation.ai;

public interface AiIndexingClient {

    void index(AiIndexingRequest request);

    default void cleanup(Long buildingId, java.util.List<String> validDocumentIds) {
        index(new AiIndexingRequest(buildingId, null, null, null, validDocumentIds));
    }
}
