package com.homes.zipsai.migration.service;

import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.homes.zipsai.migration.domain.MigrationSettings;

@Component
public class MigrationSettingsProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger(MigrationSettingsProvider.class);

    private final AtomicReference<MigrationSettings> settings = new AtomicReference<>();

    public MigrationSettings getSettings() {
        MigrationSettings currentSettings = settings.get();
        return currentSettings == null ? MigrationSettings.DEFAULT : currentSettings;
    }

    public void update(MigrationSettings newSettings) {
        MigrationSettings oldSettings = settings.getAndSet(newSettings);
        if (newSettings.equals(oldSettings)) {
            return;
        }
        LOGGER.info("이전 설정을 적용했습니다. mode={}, switchAt={}, workerMode={}, 이전 설정={}",
            newSettings.mode(), newSettings.switchAt(), newSettings.workerMode(),
            oldSettings == null ? "없음" : oldSettings);
    }
}
