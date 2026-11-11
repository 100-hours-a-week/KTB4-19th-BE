package com.homes.zipsai.conversation.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.homes.zipsai.conversation.ai.AiConverseResponse.Citation;
import com.homes.zipsai.conversation.ai.AiConverseResponse.Data;
import com.homes.zipsai.conversation.ai.AiConverseResponse.QaCardDraft;
import com.homes.zipsai.conversation.ai.AiConverseResponse.Result;

class AiConverseResponseRouteCompletionTest {

    @Test
    @DisplayName("민원은 모아야 할 정보가 남아 있으면 종료하지 않는다")
    void keepsComplaintOpenWhileFieldsMissing() {
        assertThat(complaint(List.of("location")).isConversationComplete()).isFalse();
    }

    @Test
    @DisplayName("민원은 모아야 할 정보가 없으면 종료한다")
    void completesComplaintWhenNoFieldMissing() {
        assertThat(complaint(List.of()).isConversationComplete()).isTrue();
    }

    @Test
    @DisplayName("질의는 답변 근거가 있으면 대화를 이어간다")
    void keepsKnowledgeOpenWhileCitationsExist() {
        assertThat(knowledge(List.of(citation()), "엘리베이터 점검 일정 문의").isConversationComplete()).isFalse();
    }

    @Test
    @DisplayName("질의는 답변 근거가 없고 질문 내용이 있으면 종료한다")
    void completesKnowledgeWhenNoCitationAndQuestionExists() {
        assertThat(knowledge(List.of(), "엘리베이터 점검 일정 문의").isConversationComplete()).isTrue();
    }

    @Test
    @DisplayName("질의는 답변 근거가 없어도 질문 내용이 없으면 대화를 이어간다")
    void keepsKnowledgeOpenWithoutQuestion() {
        assertThat(knowledge(List.of(), null).isConversationComplete()).isFalse();
    }

    @Test
    @DisplayName("질의는 질문 내용이 공백뿐이면 대화를 이어간다")
    void keepsKnowledgeOpenWithBlankQuestion() {
        assertThat(knowledge(List.of(), " ").isConversationComplete()).isFalse();
    }

    @Test
    @DisplayName("불명확은 민원이나 질의로 갈릴 때까지 종료하지 않는다")
    void neverCompletesWhileUnclear() {
        assertThat(clarify().isConversationComplete()).isFalse();
    }

    @Test
    @DisplayName("불명확은 다른 경로의 종료 조건을 따르지 않는다")
    void ignoresOtherRouteConditionsWhileUnclear() {
        AiConverseResponse response = response(AiRoute.CLARIFY, Result.builder().build());

        assertThat(response.isConversationComplete()).isFalse();
    }

    private static AiConverseResponse complaint(List<String> missingFields) {
        return response(AiRoute.COMPLAINT, Result.builder().missingFields(missingFields).build());
    }

    private static AiConverseResponse knowledge(List<Citation> citations, String question) {
        return response(AiRoute.KNOWLEDGE, Result.builder()
            .citations(citations)
            .qaCardDraft(question == null ? null : new QaCardDraft(question))
            .build());
    }

    private static AiConverseResponse clarify() {
        return response(AiRoute.CLARIFY, Result.builder()
            .missingFields(List.of("location"))
            .citations(List.of(citation()))
            .build());
    }

    private static Citation citation() {
        return new Citation("rule_document", "1", "관리 규약", "소음 관련 조항", null);
    }

    private static AiConverseResponse response(AiRoute route, Result result) {
        return new AiConverseResponse(AiConverseResponse.SUCCESS_CODE, "trace",
            new Data(route, AiComplaintState.COLLECTING, "답변", result));
    }
}
