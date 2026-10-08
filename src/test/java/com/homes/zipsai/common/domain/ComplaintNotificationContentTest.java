package com.homes.zipsai.common.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.homes.zipsai.building.domain.ComplaintStatus;

import tools.jackson.databind.ObjectMapper;

@DisplayName("민원 알림 내용 snapshot")
class ComplaintNotificationContentTest {

    private static final LocalDateTime OCCURRED_AT = LocalDateTime.parse("2026-10-07T18:30:00.123456");
    private final ObjectMapper json = new ObjectMapper();

    @Test
    @DisplayName("관리자 신규 민원 알림에는 확정된 필드와 생성 시각만 담는다")
    void serializesManagerComplaintCreatedContent() {
        ComplaintNotificationContent content = ComplaintNotificationContent.complaintCreated(
            101L, 10L, "천장 누수", "302", OCCURRED_AT);

        assertThat(json.readTree(json.writeValueAsString(content))).isEqualTo(json.readTree("""
            {
              "type": "COMPLAINT_CREATED",
              "complaintId": 101,
              "buildingId": 10,
              "title": "천장 누수",
              "roomNo": "302",
              "occurredAt": "2026-10-07T18:30:00.123456+09:00"
            }
            """));
    }

    @Test
    @DisplayName("입주민 상태 변경 알림에는 확정된 필드와 상태 변경 시각만 담는다")
    void serializesResidentComplaintStatusChangedContent() {
        ComplaintNotificationContent content = ComplaintNotificationContent.complaintStatusChanged(
            101L, 10L, "천장 누수", ComplaintStatus.IN_PROGRESS, OCCURRED_AT);

        assertThat(json.readTree(json.writeValueAsString(content))).isEqualTo(json.readTree("""
            {
              "type": "COMPLAINT_STATUS_CHANGED",
              "complaintId": 101,
              "buildingId": 10,
              "title": "천장 누수",
              "statusCode": "IN_PROGRESS",
              "occurredAt": "2026-10-07T18:30:00.123456+09:00"
            }
            """));
    }

    @Test
    @DisplayName("나중 상태 알림을 만들어도 저장된 이전 snapshot의 제목 상태 시각은 유지된다")
    void preservesStoredSnapshotAfterLaterComplaintChanges() {
        ComplaintNotificationContent original = ComplaintNotificationContent.complaintStatusChanged(
            101L, 10L, "천장 누수", ComplaintStatus.IN_PROGRESS, OCCURRED_AT);
        String storedContent = json.writeValueAsString(original);

        ComplaintNotificationContent later = ComplaintNotificationContent.complaintStatusChanged(
            101L, 10L, "수리 완료", ComplaintStatus.DONE, OCCURRED_AT.plusDays(1));
        ComplaintNotificationContent replay = json.readValue(storedContent, ComplaintNotificationContent.class);

        assertThat(json.readTree(json.writeValueAsString(replay))).isEqualTo(json.readTree(storedContent));
        assertThat(replay.title()).isEqualTo("천장 누수");
        assertThat(replay.statusCode()).isEqualTo(ComplaintStatus.IN_PROGRESS);
        assertThat(replay.occurredAt()).isEqualTo(OffsetDateTime.parse("2026-10-07T18:30:00.123456+09:00"));
        assertThat(later.statusCode()).isEqualTo(ComplaintStatus.DONE);
    }

    @Test
    @DisplayName("다른 오프셋으로 저장된 발생 시각도 같은 순간의 서울 시각으로 읽는다")
    void readsOccurredAtInSeoulTimeAcrossDateBoundary() {
        ComplaintNotificationContent content = json.readValue("""
            {
              "type": "COMPLAINT_STATUS_CHANGED",
              "complaintId": 101,
              "buildingId": 10,
              "title": "천장 누수",
              "statusCode": "DONE",
              "occurredAt": "2026-10-07T23:30:00Z"
            }
            """, ComplaintNotificationContent.class);

        assertThat(content.occurredAt()).isEqualTo(OffsetDateTime.parse("2026-10-08T08:30:00+09:00"));
        assertThat(json.readTree(json.writeValueAsString(content)).path("occurredAt").asText())
            .isEqualTo("2026-10-08T08:30:00+09:00");
    }

    @Test
    @DisplayName("신규 민원 발생 시각이 없으면 현재 시각으로 대체하지 않는다")
    void rejectsMissingComplaintCreatedTime() {
        assertThatThrownBy(() -> ComplaintNotificationContent.complaintCreated(
            101L, 10L, "천장 누수", "302", null))
            .isInstanceOf(NullPointerException.class).hasMessage("occurredAt");
    }

    @Test
    @DisplayName("상태 변경 시각이 없으면 현재 시각으로 대체하지 않는다")
    void rejectsMissingComplaintStatusChangedTime() {
        assertThatThrownBy(() -> ComplaintNotificationContent.complaintStatusChanged(
            101L, 10L, "천장 누수", ComplaintStatus.DONE, null))
            .isInstanceOf(NullPointerException.class).hasMessage("occurredAt");
    }

    @Test
    @DisplayName("변경 상태가 없는 상태 변경 알림은 만들지 않는다")
    void rejectsMissingChangedStatus() {
        assertThatThrownBy(() -> ComplaintNotificationContent.complaintStatusChanged(
            101L, 10L, "천장 누수", null, OCCURRED_AT))
            .isInstanceOf(NullPointerException.class).hasMessage("statusCode");
    }
}
