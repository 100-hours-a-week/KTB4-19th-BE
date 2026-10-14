package com.homes.zipsai.conversation.ai;

public interface AiComplaintFeedbackClient {

    AiComplaintFeedbackBatchResponse send(AiComplaintFeedbackBatchRequest request);
}
