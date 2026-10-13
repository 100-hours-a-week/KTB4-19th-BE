package com.homes.zipsai.migration.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.homes.zipsai.migration.domain.MigrationMode;
import com.homes.zipsai.migration.domain.MigrationSettings;
import com.homes.zipsai.migration.domain.ShadowReadResult;
import com.homes.zipsai.migration.domain.WorkerMode;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class MigrationMetricsTest {

    MigrationSettingsProvider migrationSettingsProvider;

    MeterRegistry meterRegistry;

    MigrationMetrics migrationMetrics;

    @BeforeEach
    void setUp() {
        migrationSettingsProvider = new MigrationSettingsProvider();
        meterRegistry = new SimpleMeterRegistry();
        migrationMetrics = new MigrationMetrics(meterRegistry, migrationSettingsProvider);
    }

    @Test
    @DisplayName("현재 Writer인 DB만 1로 보인다")
    void showsCurrentWriter() {
        givenSettings(MigrationMode.V2_PRIMARY, null, 0);

        assertThat(writer("V2")).isEqualTo(1);
        assertThat(writer("V1")).isZero();
    }

    @Test
    @DisplayName("전환 시각 전에는 이전 Writer를 보인다")
    void showsPreviousWriterBeforeSwitchTime() {
        givenSettings(MigrationMode.V2_PRIMARY, OffsetDateTime.now().plusDays(1), 0);

        assertThat(writer("V1")).isEqualTo(1);
        assertThat(writer("V2")).isZero();
    }

    @Test
    @DisplayName("V1_PRIMARY면 설정한 V2 읽기 비율을 보인다")
    void showsConfiguredReadPercent() {
        givenSettings(MigrationMode.V1_PRIMARY, null, 30);

        assertThat(meterRegistry.get("migration.v2.read.percent").gauge().value()).isEqualTo(30);
    }

    @Test
    @DisplayName("V2_PRIMARY면 실제 V2 읽기 비율 100을 보인다")
    void showsFullReadPercentWhenV2IsPrimary() {
        givenSettings(MigrationMode.V2_PRIMARY, null, 30);

        assertThat(meterRegistry.get("migration.v2.read.percent").gauge().value()).isEqualTo(100);
    }

    @Test
    @DisplayName("Shadow Read 비교 결과를 결과별로 센다")
    void countsShadowReadByResult() {
        migrationMetrics.recordShadowRead(ShadowReadResult.MISMATCH_PERSISTENT);

        assertThat(meterRegistry.get("migration.shadow.read").tag("result", "mismatch_persistent").counter().count())
            .isEqualTo(1);
    }

    private void givenSettings(MigrationMode mode, OffsetDateTime switchAt, int v2ReadPercent) {
        migrationSettingsProvider.update(
            new MigrationSettings(mode, switchAt, WorkerMode.PAUSED, false, false, v2ReadPercent, false));
    }

    private double writer(String database) {
        return meterRegistry.get("migration.writer").tag("database", database).gauge().value();
    }
}
