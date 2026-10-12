package com.homes.zipsai.conversation.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StubAiConverseClientReplyDeltaTest {

    private final StubAiConverseClient stubAiConverseClient =
        new StubAiConverseClient(Duration.ZERO, 0, Duration.ZERO, 0, Duration.ZERO);

    @Test
    @DisplayName("답변을 두 글자씩 나눠 보내고 이어 붙이면 최종 답변과 같다")
    void streamsReplyInTwoCharacterDeltas() {
        AiConverseRequest request = new AiConverseRequest(1L, "101", "1", "1", "turn-1", "trace-1", null, null,
            new AiConverseRequest.MessagePayload("1", "엘리베이터가 멈췄어요", List.of()), List.of(), null);
        List<String> deltas = new ArrayList<>();

        AiConverseResponse response = stubAiConverseClient.converse(request, deltas::add);

        assertThat(String.join("", deltas)).isEqualTo(response.reply());
        assertThat(deltas).allSatisfy(delta -> assertThat(delta).hasSizeBetween(1, 2));
    }
}
