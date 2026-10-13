package com.homes.zipsai.migration.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MigrationSettingsTest {

    @Test
    @DisplayName("방향별 워커 여부는 정방향과 역방향 값을 따로 본다")
    void checksWorkerFlagByDirection() {
        MigrationSettings settings = new MigrationSettings(MigrationMode.V1_PRIMARY, null, WorkerMode.RUNNING, true,
            false, 0, false);

        assertThat(settings.isWorkerEnabled(OutboxDirection.V1_TO_V2)).isTrue();
        assertThat(settings.isWorkerEnabled(OutboxDirection.V2_TO_V1)).isFalse();
    }
}
