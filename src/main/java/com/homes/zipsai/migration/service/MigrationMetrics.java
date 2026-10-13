package com.homes.zipsai.migration.service;

import java.time.OffsetDateTime;

import org.springframework.stereotype.Component;

import com.homes.zipsai.global.util.TimeUtils;
import com.homes.zipsai.migration.config.ConditionalOnMigrationEnabled;
import com.homes.zipsai.migration.domain.MigrationDatabase;
import com.homes.zipsai.migration.domain.ShadowReadResult;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

@Component
@ConditionalOnMigrationEnabled
public class MigrationMetrics {

    private final MeterRegistry meterRegistry;
    private final MigrationSettingsProvider migrationSettingsProvider;

    public MigrationMetrics(MeterRegistry meterRegistry, MigrationSettingsProvider migrationSettingsProvider) {
        this.meterRegistry = meterRegistry;
        this.migrationSettingsProvider = migrationSettingsProvider;
        for (MigrationDatabase database : MigrationDatabase.values()) {
            Gauge.builder("migration.writer", () -> isWriter(database) ? 1 : 0)
                .tag("database", database.name())
                .register(meterRegistry);
        }
        Gauge.builder("migration.v2.read.percent", () -> migrationSettingsProvider.getSettings().v2ReadPercentAt(now()))
            .register(meterRegistry);
    }

    public void recordShadowRead(ShadowReadResult result) {
        meterRegistry.counter("migration.shadow.read", "result", result.getTag()).increment();
    }

    private boolean isWriter(MigrationDatabase database) {
        return migrationSettingsProvider.getSettings().modeAt(now()).getPrimaryDatabase() == database;
    }

    private OffsetDateTime now() {
        return TimeUtils.toOffsetDateTime(TimeUtils.now());
    }
}
