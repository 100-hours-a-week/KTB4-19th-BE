package com.homes.zipsai.migration.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.homes.zipsai.migration.domain.MigrationMode;
import com.homes.zipsai.migration.domain.MigrationSettings;
import com.homes.zipsai.migration.domain.OutboxDirection;
import com.homes.zipsai.migration.domain.OutboxEvent;
import com.homes.zipsai.migration.domain.OutboxStatus;
import com.homes.zipsai.migration.domain.WorkerMode;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

@ExtendWith(MockitoExtension.class)
class OutboxWorkerMetricsTest {

    private static final LocalDateTime NOON = LocalDateTime.of(2026, 10, 13, 12, 0);

    @Mock
    DataSource v1DataSource;

    @Mock
    DataSource v2DataSource;

    MigrationSettingsProvider migrationSettingsProvider;

    MeterRegistry meterRegistry;

    OutboxWorkerMetrics outboxWorkerMetrics;

    @BeforeEach
    void setUp() {
        migrationSettingsProvider = new MigrationSettingsProvider();
        meterRegistry = new SimpleMeterRegistry();
        outboxWorkerMetrics = new OutboxWorkerMetrics(meterRegistry, migrationSettingsProvider, v1DataSource,
            v2DataSource);
    }

    @Test
    @DisplayName("반영에 성공하면 방향별 성공 횟수를 센다")
    void countsSuccessByDirection() {
        outboxWorkerMetrics.recordApplied(event(NOON.minusSeconds(3)), NOON);

        assertThat(workerEvents("success")).isEqualTo(1);
    }

    @Test
    @DisplayName("반영에 성공하면 이벤트 생성부터 반영까지 걸린 시간을 남긴다")
    void recordsApplyLag() {
        outboxWorkerMetrics.recordApplied(event(NOON.minusSeconds(3)), NOON);

        Timer applyLag = meterRegistry.get("migration.apply.lag").tag("direction", "V1_TO_V2").timer();
        assertThat(applyLag.max(TimeUnit.SECONDS)).isEqualTo(3);
    }

    @Test
    @DisplayName("재시도와 최종 실패를 따로 센다")
    void countsRetryAndFailureSeparately() {
        outboxWorkerMetrics.recordRetry(OutboxDirection.V1_TO_V2);
        outboxWorkerMetrics.recordFailure(OutboxDirection.V1_TO_V2);

        assertThat(workerEvents("retry")).isEqualTo(1);
        assertThat(workerEvents("failure")).isEqualTo(1);
    }

    @Test
    @DisplayName("워커가 실행 중이고 그 방향이 켜져 있을 때만 활성으로 보인다")
    void showsWorkerActiveByDirection() {
        migrationSettingsProvider.update(
            new MigrationSettings(MigrationMode.V1_PRIMARY, null, WorkerMode.RUNNING, true, false, 0, false));

        assertThat(workerActive("V1_TO_V2")).isEqualTo(1);
        assertThat(workerActive("V2_TO_V1")).isZero();
    }

    @Test
    @DisplayName("워커가 멈춰 있으면 방향이 켜져 있어도 비활성으로 보인다")
    void showsWorkerInactiveWhenPaused() {
        migrationSettingsProvider.update(
            new MigrationSettings(MigrationMode.V1_PRIMARY, null, WorkerMode.PAUSED, true, true, 0, false));

        assertThat(workerActive("V1_TO_V2")).isZero();
    }

    private OutboxEvent event(LocalDateTime createdAt) {
        return new OutboxEvent(10L, OutboxDirection.V1_TO_V2, "Users", "1", OutboxStatus.PENDING, 0, null, createdAt);
    }

    private double workerEvents(String outcome) {
        return meterRegistry.get("migration.worker.events").tag("direction", "V1_TO_V2").tag("outcome", outcome)
            .counter().count();
    }

    private double workerActive(String direction) {
        return meterRegistry.get("migration.worker.active").tag("direction", direction).gauge().value();
    }
}
