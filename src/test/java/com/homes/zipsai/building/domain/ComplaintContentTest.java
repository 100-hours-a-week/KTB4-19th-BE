package com.homes.zipsai.building.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.homes.zipsai.conversation.ai.AiComplaintDraft;

class ComplaintContentTest {

    private static final OffsetDateTime YESTERDAY_EVENING = OffsetDateTime.parse("2026-09-15T20:00:00+09:00");

    @Test
    @DisplayName("증상을 민원 제목으로 쓴다")
    void usesSymptomAsTitle() {
        ComplaintContent complaintContent = ComplaintContent.from(
            new AiComplaintDraft("안방 천장", "천장에서 물이 샘", YESTERDAY_EVENING));

        assertThat(complaintContent.title()).isEqualTo("천장에서 물이 샘");
    }

    @Test
    @DisplayName("모은 위치, 시점, 증상을 한 줄 요약으로 합친다")
    void joinsCollectedValuesIntoSummary() {
        ComplaintContent complaintContent = ComplaintContent.from(
            new AiComplaintDraft("안방 천장", "천장에서 물이 샘", YESTERDAY_EVENING));

        assertThat(complaintContent.aiSummary()).isEqualTo("위치: 안방 천장 / 시점: 9월 15일 20:00 / 증상: 천장에서 물이 샘");
    }

    @Test
    @DisplayName("모은 발생 시점을 그대로 쓴다")
    void keepsCollectedOccurredTime() {
        ComplaintContent complaintContent = ComplaintContent.from(
            new AiComplaintDraft("안방 천장", "천장에서 물이 샘", YESTERDAY_EVENING));

        assertThat(complaintContent.occurredTime()).isEqualTo(YESTERDAY_EVENING);
    }

    @Test
    @DisplayName("증상이 없으면 위치를 민원 제목으로 쓴다")
    void usesLocationAsTitleWhenSymptomMissing() {
        ComplaintContent complaintContent = ComplaintContent.from(new AiComplaintDraft("공동현관", null, null));

        assertThat(complaintContent.title()).isEqualTo("공동현관");
    }

    @Test
    @DisplayName("모으지 못한 위치와 증상은 미상으로 채운다")
    void fillsMissingValuesWithUnknown() {
        ComplaintContent complaintContent = ComplaintContent.from(AiComplaintDraft.EMPTY);

        assertThat(complaintContent.location()).isEqualTo("미상");
        assertThat(complaintContent.symptom()).isEqualTo("미상");
        assertThat(complaintContent.occurredTime()).isNull();
    }

    @Test
    @DisplayName("아무것도 모으지 못하면 기본 제목과 요약을 쓴다")
    void usesDefaultTitleWhenNothingCollected() {
        ComplaintContent complaintContent = ComplaintContent.from(AiComplaintDraft.EMPTY);

        assertThat(complaintContent.title()).isEqualTo("민원 접수");
        assertThat(complaintContent.aiSummary()).isEqualTo("민원 접수");
    }

    @Test
    @DisplayName("민원 제목은 50자로 자른다")
    void truncatesTitleTo50Characters() {
        ComplaintContent complaintContent = ComplaintContent.from(
            new AiComplaintDraft("가".repeat(50), "다".repeat(100), YESTERDAY_EVENING));

        assertThat(complaintContent.title()).hasSize(50);
    }

    @Test
    @DisplayName("요약은 200자를 넘지 않고 증상은 잘리지 않는다")
    void keepsSymptomWhileTruncatingSummaryTo200Characters() {
        ComplaintContent complaintContent = ComplaintContent.from(
            new AiComplaintDraft("가".repeat(50), "다".repeat(100), YESTERDAY_EVENING));

        assertThat(complaintContent.aiSummary()).hasSizeLessThanOrEqualTo(200).contains("다".repeat(100));
    }
}
