package com.homes.zipsai.building.domain;

import com.homes.zipsai.conversation.ai.AiRoute;

public enum ComplaintType {
    COMPLAINT,
    QA;

    public static ComplaintType from(AiRoute route) {
        return route == AiRoute.KNOWLEDGE ? QA : COMPLAINT;
    }
}
