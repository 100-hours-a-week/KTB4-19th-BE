package com.homes.zipsai.building.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.homes.zipsai.conversation.ai.AiRoute;

class ComplaintTypeTest {

    @Test
    @DisplayName("질의 경로에서 접수한 민원은 QA다")
    void returnsQaForKnowledgeRoute() {
        assertThat(ComplaintType.from(AiRoute.KNOWLEDGE)).isEqualTo(ComplaintType.QA);
    }

    @Test
    @DisplayName("민원 경로에서 접수한 민원은 일반 민원이다")
    void returnsComplaintForComplaintRoute() {
        assertThat(ComplaintType.from(AiRoute.COMPLAINT)).isEqualTo(ComplaintType.COMPLAINT);
    }
}
