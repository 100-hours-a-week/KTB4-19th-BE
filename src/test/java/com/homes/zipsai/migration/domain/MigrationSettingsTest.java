package com.homes.zipsai.migration.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MigrationSettingsTest {

    private static final OffsetDateTime SWITCH_AT = OffsetDateTime.parse("2026-10-20T14:00:00+09:00");

    @Test
    @DisplayName("전환 시각이 없으면 지정한 mode를 바로 쓴다")
    void usesModeWithoutSwitchTime() {
        MigrationSettings settings = settings(MigrationMode.V2_PRIMARY, null);

        assertThat(settings.modeAt(SWITCH_AT.minusHours(1))).isEqualTo(MigrationMode.V2_PRIMARY);
    }

    @Test
    @DisplayName("전환 시각 전에는 반대 mode를 쓴다")
    void usesOppositeModeBeforeSwitchTime() {
        MigrationSettings settings = settings(MigrationMode.V2_PRIMARY, SWITCH_AT);

        assertThat(settings.modeAt(SWITCH_AT.minusSeconds(1))).isEqualTo(MigrationMode.V1_PRIMARY);
    }

    @Test
    @DisplayName("전환 시각부터는 지정한 mode를 쓴다")
    void usesModeFromSwitchTime() {
        MigrationSettings settings = settings(MigrationMode.V2_PRIMARY, SWITCH_AT);

        assertThat(settings.modeAt(SWITCH_AT)).isEqualTo(MigrationMode.V2_PRIMARY);
    }

    @Test
    @DisplayName("방향별 워커 여부는 정방향과 역방향 값을 따로 본다")
    void checksWorkerFlagByDirection() {
        MigrationSettings settings = new MigrationSettings(MigrationMode.V1_PRIMARY, null, WorkerMode.RUNNING, true,
            false, 0, false);

        assertThat(settings.isWorkerEnabled(OutboxDirection.V1_TO_V2)).isTrue();
        assertThat(settings.isWorkerEnabled(OutboxDirection.V2_TO_V1)).isFalse();
    }

    private MigrationSettings settings(MigrationMode mode, OffsetDateTime switchAt) {
        return new MigrationSettings(mode, switchAt, WorkerMode.PAUSED, false, false, 0, false);
    }
}
