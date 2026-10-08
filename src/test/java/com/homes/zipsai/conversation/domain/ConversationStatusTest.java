package com.homes.zipsai.conversation.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.homes.zipsai.conversation.ai.AiRoute;

class ConversationStatusTest {

    @Test
    @DisplayName("QA로 접수한 대화는 질문 전달 완료로 표시한다")
    void labelsQuestionDelivered() {
        assertThat(ConversationStatus.COMPLAINT_CREATED.labelFor(AiRoute.KNOWLEDGE)).isEqualTo("질문 전달 완료");
    }

    @Test
    @DisplayName("민원으로 접수한 대화는 민원 생성 완료로 표시한다")
    void labelsComplaintCreated() {
        assertThat(ConversationStatus.COMPLAINT_CREATED.labelFor(AiRoute.COMPLAINT)).isEqualTo("민원 생성 완료");
    }

    @Test
    @DisplayName("경로 정보가 없는 배포 전 접수 대화는 민원 생성 완료로 표시한다")
    void labelsComplaintCreatedWithoutRoute() {
        assertThat(ConversationStatus.COMPLAINT_CREATED.labelFor(null)).isEqualTo("민원 생성 완료");
    }

    @Test
    @DisplayName("진행 중인 대화는 질의 경로여도 진행중으로 표시한다")
    void labelsActiveRegardlessOfRoute() {
        assertThat(ConversationStatus.ACTIVE.labelFor(AiRoute.KNOWLEDGE)).isEqualTo("진행중");
    }
}
