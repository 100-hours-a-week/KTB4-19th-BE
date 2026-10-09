package com.homes.zipsai.conversation.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StubAiConverseClientImageTest {

    private final StubAiConverseClient stubAiConverseClient =
        new StubAiConverseClient(Duration.ZERO, 0, Duration.ZERO, 0);

    @Test
    @DisplayName("이번 메시지 사진마다 분석 결과를 돌려준다")
    void analyzesEachCurrentImage() {
        AiConverseRequest request = complaintRequest(null, new AiConverseRequest.MessageImage(32L, "https://s3.test/a.jpg"));

        AiConverseResponse response = stubAiConverseClient.converse(request);

        assertThat(response.imageObservations()).extracting(observation -> observation.attachmentId())
            .containsExactly(32L);
    }

    @Test
    @DisplayName("초안의 사진 ID에 이번 메시지 사진을 이어 붙인다")
    void appendsCurrentImagesToDraftAttachmentIds() {
        AiConverseRequest.ComplaintDraftPayload previousDraft = AiConverseRequest.ComplaintDraftPayload.builder()
            .symptom("천장에서 물이 새요")
            .attachmentIds(List.of(31L))
            .build();
        AiConverseRequest request =
            complaintRequest(previousDraft, new AiConverseRequest.MessageImage(32L, "https://s3.test/a.jpg"));

        AiConverseResponse response = stubAiConverseClient.converse(request);

        assertThat(response.complaintDraft().attachmentIds()).containsExactly(31L, 32L);
    }

    private static AiConverseRequest complaintRequest(AiConverseRequest.ComplaintDraftPayload previousDraft,
                                                      AiConverseRequest.MessageImage image) {
        return new AiConverseRequest(1L, "302", "7", "11", "turn-1", "trace-1",
            AiRoute.COMPLAINT, AiComplaintState.COLLECTING,
            new AiConverseRequest.MessagePayload("21", "안방 천장이요", List.of(image)), List.of(), previousDraft);
    }
}
