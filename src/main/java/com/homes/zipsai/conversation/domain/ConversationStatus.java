package com.homes.zipsai.conversation.domain;

import com.homes.zipsai.conversation.ai.AiRoute;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ConversationStatus {
    ACTIVE("진행중"),
    COMPLAINT_CREATED("민원 생성 완료"),
    CLOSED("대화 종료");

    private static final String QUESTION_DELIVERED_LABEL = "질문 전달 완료";

    private final String label;

    public String labelFor(AiRoute currentRoute) {
        if (this == COMPLAINT_CREATED && currentRoute == AiRoute.KNOWLEDGE) {
            return QUESTION_DELIVERED_LABEL;
        }
        return label;
    }
}
