package com.homes.zipsai.migration.service;

import java.time.OffsetDateTime;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.homes.zipsai.global.util.TimeUtils;
import com.homes.zipsai.migration.domain.MigrationDatabase;
import com.homes.zipsai.migration.domain.MigrationMode;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class MigrationRouting {

    private static final String WRITE_DATABASE_KEY = MigrationRouting.class.getName() + ".writeDatabase";

    private final MigrationSettingsProvider migrationSettingsProvider;

    public MigrationDatabase determineWriteDatabase() {
        MigrationDatabase primaryDatabase = currentMode().getPrimaryDatabase();
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return primaryDatabase;
        }
        MigrationDatabase boundDatabase = (MigrationDatabase) TransactionSynchronizationManager.getResource(
            WRITE_DATABASE_KEY);
        if (boundDatabase != null) {
            return boundDatabase;
        }
        TransactionSynchronizationManager.bindResource(WRITE_DATABASE_KEY, primaryDatabase);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                TransactionSynchronizationManager.unbindResourceIfPossible(WRITE_DATABASE_KEY);
            }
        });
        return primaryDatabase;
    }

    private MigrationMode currentMode() {
        return migrationSettingsProvider.getSettings().modeAt(now());
    }

    private OffsetDateTime now() {
        return TimeUtils.toOffsetDateTime(TimeUtils.now());
    }
}
