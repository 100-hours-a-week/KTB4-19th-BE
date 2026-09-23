package com.homes.zipsai.conversation.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.homes.zipsai.conversation.ai.AiConverseResponse.Citation;
import com.homes.zipsai.conversation.ai.AiConverseResponse.Data;
import com.homes.zipsai.conversation.ai.AiConverseResponse.Result;

class AiConverseRouteCompletionTest {

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
        assertThat(knowledge(List.of(citation())).isConversationComplete()).isFalse();
    }

    @Test
    @DisplayName("질의는 답변 근거가 없으면 종료한다")
    void completesKnowledgeWhenNoCitation() {
        assertThat(knowledge(List.of()).isConversationComplete()).isTrue();
    }

    @Test
    @DisplayName("불명확은 민원이나 질의로 갈릴 때까지 종료하지 않는다")
    void neverCompletesWhileUnclear() {
        assertThat(clarify().isConversationComplete()).isFalse();
    }

    @Test
    @DisplayName("불명확은 다른 경로의 종료 조건을 따르지 않는다")
    void ignoresOtherRouteConditionsWhileUnclear() {
        AiConverseResponse response = response(AiRoute.CLARIFY, new Result(null, null, List.of(), List.of()));

        assertThat(response.isConversationComplete()).isFalse();
    }

    private static AiConverseResponse complaint(List<String> missingFields) {
        return response(AiRoute.COMPLAINT, new Result(null, null, missingFields, List.of()));
    }

    private static AiConverseResponse knowledge(List<Citation> citations) {
        return response(AiRoute.KNOWLEDGE, new Result(null, null, List.of(), citations));
    }

    private static AiConverseResponse clarify() {
        return response(AiRoute.CLARIFY, new Result(null, null, List.of("location"), List.of(citation())));
    }

    private static Citation citation() {
        return new Citation("rule_document", "1", "관리 규약", "소음 관련 조항", null);
    }

    private static AiConverseResponse response(AiRoute route, Result result) {
        return new AiConverseResponse(AiConverseResponse.SUCCESS_CODE, "trace",
            new Data(route, AiComplaintState.COLLECTING, "답변", result));
    }
}
