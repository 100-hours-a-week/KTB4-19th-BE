package com.homes.zipsai.migration.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.homes.zipsai.migration.domain.MigrationDatabase;
import com.homes.zipsai.migration.domain.MigrationMode;
import com.homes.zipsai.migration.domain.MigrationSettings;
import com.homes.zipsai.migration.domain.OutboxDirection;
import com.homes.zipsai.migration.domain.OutboxEvent;
import com.homes.zipsai.migration.domain.OutboxStatus;
import com.homes.zipsai.migration.domain.ShadowReadComparison;
import com.homes.zipsai.migration.domain.ShadowReadComparison.FieldDifference;
import com.homes.zipsai.migration.domain.ShadowReadPhase;
import com.homes.zipsai.migration.domain.ShadowReadQuery;
import com.homes.zipsai.migration.domain.ShadowReadRequest;
import com.homes.zipsai.migration.domain.ShadowReadResult;
import com.homes.zipsai.migration.domain.ShadowReadRowCheck;
import com.homes.zipsai.migration.domain.WorkerMode;

import tools.jackson.databind.json.JsonMapper;

@ExtendWith(OutputCaptureExtension.class)
class MigrationEventLoggerTest {

    private static final OutboxEvent EVENT = new OutboxEvent(10L, OutboxDirection.V1_TO_V2, "Users", "1",
        OutboxStatus.PENDING, 0, null, LocalDateTime.of(2026, 10, 13, 12, 0));
    private static final String METHOD = "ConversationService.getConversations(..)";
    private static final ShadowReadRequest REQUEST = new ShadowReadRequest("trace-1", METHOD, MigrationDatabase.V1);
    private static final ShadowReadQuery V1_QUERY = new ShadowReadQuery(MigrationDatabase.V1,
        Instant.parse("2026-10-14T12:00:00Z"), Instant.parse("2026-10-14T12:00:01Z"), null);
    private static final ShadowReadQuery V2_QUERY = new ShadowReadQuery(MigrationDatabase.V2,
        Instant.parse("2026-10-14T12:00:01Z"), Instant.parse("2026-10-14T12:00:02Z"), null);

    MigrationEventLogger migrationEventLogger = new MigrationEventLogger(JsonMapper.builder().build());

    @Test
    @DisplayName("반영 성공은 이벤트 번호, 방향, 처리 시간을 JSON으로 남긴다")
    void logsAppliedEvent(CapturedOutput output) {
        migrationEventLogger.outboxApplied(EVENT, 15, 3000);

        assertThat(output.getOut()).contains("\"event\":\"outbox_applied\"", "\"event_id\":10",
            "\"direction\":\"V1_TO_V2\"", "\"outcome\":\"success\"", "\"duration_ms\":15", "\"lag_ms\":3000");
    }

    @Test
    @DisplayName("재시도는 재시도 횟수와 실패 이유를 JSON으로 남긴다")
    void logsRetryEvent(CapturedOutput output) {
        migrationEventLogger.outboxRetry(EVENT, 2, "timeout");

        assertThat(output.getOut()).contains("\"event\":\"outbox_retry\"", "\"outcome\":\"retry\"",
            "\"retry_count\":2", "\"error\":\"timeout\"");
    }

    @Test
    @DisplayName("최종 실패는 failure 결과로 JSON을 남긴다")
    void logsFailedEvent(CapturedOutput output) {
        migrationEventLogger.outboxFailed(EVENT, 5, "duplicate key");

        assertThat(output.getOut()).contains("\"event\":\"outbox_failed\"", "\"outcome\":\"failure\"",
            "\"retry_count\":5");
    }

    @Test
    @DisplayName("실제 읽기는 요청 ID, 응답 DB, 조회 시각을 JSON으로 남긴다")
    void logsServedRead(CapturedOutput output) {
        migrationEventLogger.readServed("trace-1", METHOD, V2_QUERY);

        assertThat(output.getOut()).contains("\"event\":\"migration_read\"", "\"trace_id\":\"trace-1\"",
            "\"response_database\":\"V2\"", "\"started_at\":\"2026-10-14T12:00:01Z\"",
            "\"finished_at\":\"2026-10-14T12:00:02Z\"");
    }

    @Test
    @DisplayName("Shadow Read 불일치는 단계, DB별 조회 시각, 차이를 JSON으로 남긴다")
    void logsShadowReadMismatch(CapturedOutput output) {
        ShadowReadComparison comparison = ShadowReadComparison.of(V1_QUERY, V2_QUERY, "conversationId", List.of("3"),
            List.of(), false,
            List.of(new FieldDifference("1", "lastMessageAt", "2026-10-14T12:00:05", "2026-10-14T12:00:02")));

        migrationEventLogger.shadowReadMismatch(REQUEST, ShadowReadPhase.RECOMPARE,
            ShadowReadResult.MISMATCH_PERSISTENT, comparison);

        assertThat(output.getOut()).contains("\"event\":\"shadow_read_mismatch\"", "\"trace_id\":\"trace-1\"",
            "\"method\":\"" + METHOD + "\"", "\"phase\":\"recompare\"", "\"response_database\":\"V1\"",
            "\"outcome\":\"mismatch_persistent\"", "\"v1_started_at\":\"2026-10-14T12:00:00Z\"",
            "\"v2_finished_at\":\"2026-10-14T12:00:02Z\"", "\"only_in_v1_ids\":[\"3\"]", "\"only_in_v1_count\":1",
            "\"order_differs\":false", "\"field\":\"lastMessageAt\"", "\"v1\":\"2026-10-14T12:00:05\"",
            "\"field_difference_count\":1");
    }

    @Test
    @DisplayName("PK 재확인 결과는 테이블과 ID별 판정, 컬럼 차이를 JSON으로 남긴다")
    void logsShadowReadRowCheck(CapturedOutput output) {
        ShadowReadRowCheck rowCheck = ShadowReadRowCheck.of("Conversations", Instant.parse("2026-10-14T12:00:30Z"),
            Instant.parse("2026-10-14T12:00:31Z"), List.of("111783"), List.of("2"), List.of("3"),
            List.of(new FieldDifference("3", "last_message_at", "2026-10-14T12:00:05", "2026-10-14T12:00:02")));

        migrationEventLogger.shadowReadRowChecked(REQUEST, rowCheck);

        assertThat(output.getOut()).contains("\"event\":\"shadow_read_mismatch\"", "\"phase\":\"recompare\"",
            "\"outcome\":\"mismatch_persistent\"", "\"recheck\":\"primary_key\"", "\"table\":\"Conversations\"",
            "\"rows_started_at\":\"2026-10-14T12:00:30Z\"", "\"resolved_ids\":[\"111783\"]",
            "\"lagging_ids\":[\"2\"]", "\"persistent_ids\":[\"3\"]", "\"field\":\"last_message_at\"",
            "\"column_difference_count\":1");
    }

    @Test
    @DisplayName("Shadow Read 조회 실패는 단계와 오류를 JSON으로 남긴다")
    void logsShadowReadError(CapturedOutput output) {
        migrationEventLogger.shadowReadError(REQUEST, ShadowReadPhase.INITIAL, new IllegalStateException("V2 down"));

        assertThat(output.getOut()).contains("\"event\":\"shadow_read_error\"", "\"phase\":\"initial\"",
            "\"outcome\":\"error\"", "V2 down");
    }

    @Test
    @DisplayName("대기열이 가득 차 건너뛴 Shadow Read는 단계와 이유를 JSON으로 남긴다")
    void logsShadowReadSkipped(CapturedOutput output) {
        migrationEventLogger.shadowReadSkipped(REQUEST, ShadowReadPhase.RECOMPARE);

        assertThat(output.getOut()).contains("\"event\":\"shadow_read_skipped\"", "\"phase\":\"recompare\"",
            "\"outcome\":\"skipped\"", "\"reason\":\"queue_full\"");
    }

    @Test
    @DisplayName("설정 변경은 mode와 전환 시각, 워커 설정을 JSON으로 남긴다")
    void logsSettingsChanged(CapturedOutput output) {
        migrationEventLogger.settingsChanged(new MigrationSettings(MigrationMode.V2_PRIMARY,
            OffsetDateTime.parse("2026-10-20T14:00:00+09:00"), WorkerMode.RUNNING, true, false, 30, 100));

        assertThat(output.getOut()).contains("\"event\":\"migration_settings_changed\"", "\"mode\":\"V2_PRIMARY\"",
            "\"switch_at\":\"2026-10-20T14:00+09:00\"", "\"forward_worker_enabled\":true", "\"v2_read_percent\":30");
    }
}
