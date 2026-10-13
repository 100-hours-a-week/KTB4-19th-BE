package com.homes.zipsai.migration.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.homes.zipsai.migration.domain.MigrationMode;
import com.homes.zipsai.migration.domain.MigrationSettings;
import com.homes.zipsai.migration.domain.WorkerMode;

class MigrationSettingsProviderTest {

    @Test
    @DisplayName("설정을 받기 전에는 기록을 끄고 워커를 멈춘 기본 설정을 쓴다")
    void usesDefaultSettingsBeforeFirstUpdate() {
        MigrationSettingsProvider migrationSettingsProvider = new MigrationSettingsProvider();

        MigrationSettings settings = migrationSettingsProvider.getSettings();

        assertThat(settings)
            .isEqualTo(
                new MigrationSettings(MigrationMode.V1_PRIMARY, null, WorkerMode.PAUSED, false, false, 0, false));
    }
}
