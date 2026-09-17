package com.homes.zipsai.building.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.Test;

import com.homes.zipsai.conversation.ai.AiComplaintDraft;

class ComplaintContentTest {

    private static final OffsetDateTime YESTERDAY_EVENING = OffsetDateTime.parse("2026-09-15T20:00:00+09:00");

    @Test
    void usesSymptomAsTitleAndJoinsCollectedValuesIntoSummary() {
        ComplaintContent content = ComplaintContent.from(
            new AiComplaintDraft("안방 천장", "천장에서 물이 샘", YESTERDAY_EVENING));

        assertThat(content.title()).isEqualTo("천장에서 물이 샘");
        assertThat(content.aiSummary()).isEqualTo("위치: 안방 천장 / 시점: 9월 15일 20:00 / 증상: 천장에서 물이 샘");
        assertThat(content.occurredTime()).isEqualTo(YESTERDAY_EVENING);
    }

    @Test
    void fillsMissingValuesWithUnknownAndFallsBackToLocationOrDefaultTitle() {
        ComplaintContent onlyLocation = ComplaintContent.from(
            new AiComplaintDraft("공동현관", null, null));
        assertThat(onlyLocation.title()).isEqualTo("공동현관");
        assertThat(onlyLocation.symptom()).isEqualTo("미상");
        assertThat(onlyLocation.occurredTime()).isNull();

        ComplaintContent nothing = ComplaintContent.from(AiComplaintDraft.EMPTY);
        assertThat(nothing.title()).isEqualTo("민원 접수");
        assertThat(nothing.aiSummary()).isEqualTo("민원 접수");
        assertThat(nothing.location()).isEqualTo("미상");
    }

    @Test
    void truncatesTitleAndSummaryToColumnLength() {
        ComplaintContent content = ComplaintContent.from(
            new AiComplaintDraft("가".repeat(50), "다".repeat(100), YESTERDAY_EVENING));

        assertThat(content.title()).hasSize(50);
        assertThat(content.aiSummary()).hasSizeLessThanOrEqualTo(200).contains("다".repeat(100));
    }
}
