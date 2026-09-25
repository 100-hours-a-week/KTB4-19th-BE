package com.homes.zipsai.conversation.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ConversationStatus {
    ACTIVE("진행중"),
    COMPLAINT_CREATED("민원접수");

    private final String label;
}
