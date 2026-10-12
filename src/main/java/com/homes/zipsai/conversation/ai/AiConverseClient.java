package com.homes.zipsai.conversation.ai;

import java.util.function.Consumer;

public interface AiConverseClient {

    AiConverseResponse converse(AiConverseRequest request, Consumer<String> onReplyDelta);
}
