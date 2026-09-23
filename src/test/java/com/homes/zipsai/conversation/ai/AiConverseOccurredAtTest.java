package com.homes.zipsai.conversation.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.homes.zipsai.conversation.ai.AiConverseResponse.DraftPatch;

class AiConverseOccurredAtTest {

    @Test
    @DisplayName("오프셋이 없는 시각은 한국 시간으로 읽는다")
    void readsTimeWithoutOffsetAsKoreanTime() {
        AiComplaintDraft draft = draftOf("2026-09-23T00:00:00");

        assertThat(draft.occurredAt()).isEqualTo(OffsetDateTime.parse("2026-09-23T00:00:00+09:00"));
    }

    @Test
    @DisplayName("오프셋이 있는 시각은 그대로 읽는다")
    void readsTimeWithOffset() {
        AiComplaintDraft draft = draftOf("2026-09-23T10:30:00+09:00");

        assertThat(draft.occurredAt()).isEqualTo(OffsetDateTime.parse("2026-09-23T10:30:00+09:00"));
    }

    @Test
    @DisplayName("다른 시간대로 온 시각은 한국 시간으로 옮긴다")
    void movesOtherZoneToKoreanTime() {
        AiComplaintDraft draft = draftOf("2026-09-23T00:00:00Z");

        assertThat(draft.occurredAt()).isEqualTo(OffsetDateTime.parse("2026-09-23T09:00:00+09:00"));
    }

    @Test
    @DisplayName("시각이 없으면 비워 둔다")
    void leavesTimeEmptyWhenAbsent() {
        assertThat(draftOf(null).occurredAt()).isNull();
        assertThat(draftOf("").occurredAt()).isNull();
    }

    @Test
    @DisplayName("읽을 수 없는 시각은 대화를 멈추지 않고 비워 둔다")
    void leavesTimeEmptyWhenUnreadable() {
        assertThat(draftOf("어제 저녁").occurredAt()).isNull();
        assertThat(draftOf("2026-09").occurredAt()).isNull();
    }

    @Test
    @DisplayName("시각을 읽지 못해도 위치와 증상은 남는다")
    void keepsOtherFieldsWhenTimeIsUnreadable() {
        AiComplaintDraft draft = new DraftPatch("안방 천장", "물이 샌다", "어제 저녁").toDraft();

        assertThat(draft.location()).isEqualTo("안방 천장");
        assertThat(draft.symptom()).isEqualTo("물이 샌다");
    }

    private static AiComplaintDraft draftOf(String occurredAt) {
        return new DraftPatch(null, null, occurredAt).toDraft();
    }
}
