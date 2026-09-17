package com.homes.zipsai.conversation.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ConversationStatus {
    ACTIVE("진행중"),
    RESOLVED("답변완료"),
    COMPLAINT_CREATED("민원접수");

    private final String label;
}
