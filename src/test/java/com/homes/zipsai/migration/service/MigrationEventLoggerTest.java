package com.homes.zipsai.migration.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.homes.zipsai.migration.domain.MigrationMode;
import com.homes.zipsai.migration.domain.MigrationSettings;
import com.homes.zipsai.migration.domain.OutboxDirection;
import com.homes.zipsai.migration.domain.OutboxEvent;
import com.homes.zipsai.migration.domain.OutboxStatus;
import com.homes.zipsai.migration.domain.WorkerMode;

import tools.jackson.databind.json.JsonMapper;

@ExtendWith(OutputCaptureExtension.class)
class MigrationEventLoggerTest {

    private static final OutboxEvent EVENT = new OutboxEvent(10L, OutboxDirection.V1_TO_V2, "Users", "1",
        OutboxStatus.PENDING, 0, null, LocalDateTime.of(2026, 10, 13, 12, 0));

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
    @DisplayName("Shadow Read 불일치는 메서드와 결과를 JSON으로 남긴다")
    void logsShadowReadMismatch(CapturedOutput output) {
        migrationEventLogger.shadowReadMismatch("UserService.findUserName(..)", "mismatch_persistent");

        assertThat(output.getOut()).contains("\"event\":\"shadow_read_mismatch\"",
            "\"method\":\"UserService.findUserName(..)\"", "\"outcome\":\"mismatch_persistent\"");
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
