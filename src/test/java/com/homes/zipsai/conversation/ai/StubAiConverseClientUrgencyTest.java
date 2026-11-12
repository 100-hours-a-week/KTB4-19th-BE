package com.homes.zipsai.conversation.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class StubAiConverseClientUrgencyTest {

    private final StubAiConverseClient client =
        new StubAiConverseClient(Duration.ZERO, 0, Duration.ZERO, 0, Duration.ZERO);

    @ParameterizedTest(name = "{0}는 {1}점과 긴급 판정 {2}를 반환한다")
    @CsvSource({
        "[긴급] 천장에서 물이 쏟아져요, 80, true",
        "[우선] 현관 조명이 계속 깜빡여요, 50, false",
        "안방 벽지가 들떴어요, 20, false"
    })
    @DisplayName("민원 접수 직전 초안에 증상 입력에 따른 긴급도와 긴급 판정을 담는다")
    void includesUrgencyInCompletedComplaintDraft(String symptom, int score, boolean urgent) {
        AiConverseRequest.ComplaintDraftPayload previousDraft = AiConverseRequest.ComplaintDraftPayload.builder()
            .symptom(symptom)
            .build();
        AiConverseRequest request = new AiConverseRequest(1L, "302", "7", "11", "turn-1", "trace-1",
            AiRoute.COMPLAINT, AiComplaintState.COLLECTING,
            new AiConverseRequest.MessagePayload("21", "안방 천장이요", List.of()), List.of(), previousDraft);

        AiConverseResponse response = client.converse(request, delta -> { });

        assertThat(response.isConversationComplete()).isTrue();

        assertThat(response.complaintDraft().urgencyScore()).isEqualTo(score);

        assertThat(response.complaintDraft().isUrgent()).isEqualTo(urgent);

        assertThat(response.complaintDraft().urgencyVersion()).isNotBlank();
    }
}
