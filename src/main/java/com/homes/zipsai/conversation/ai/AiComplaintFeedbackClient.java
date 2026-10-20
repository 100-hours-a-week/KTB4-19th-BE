package com.homes.zipsai.conversation.ai;

public interface AiComplaintFeedbackClient {

    AiComplaintFeedbackResponse send(AiComplaintFeedbackBatchRequest request);

    AiComplaintFeedbackResponse delete(AiComplaintFeedbackDeleteRequest request);
}
