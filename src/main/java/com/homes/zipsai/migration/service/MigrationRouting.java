package com.homes.zipsai.migration.service;

import java.time.OffsetDateTime;
import java.util.concurrent.ThreadLocalRandom;

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
    private static final int PERCENT = 100;
    private static final ThreadLocal<MigrationDatabase> FIXED_READ_DATABASE = new ThreadLocal<>();

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

    public MigrationDatabase determineReadDatabase() {
        MigrationDatabase fixedDatabase = FIXED_READ_DATABASE.get();
        if (fixedDatabase != null) {
            return fixedDatabase;
        }
        return chooseReadDatabase();
    }

    public MigrationDatabase chooseReadDatabase() {
        int v2ReadPercent = migrationSettingsProvider.getSettings().v2ReadPercentAt(now());
        boolean readsFromV2 = ThreadLocalRandom.current().nextInt(PERCENT) < v2ReadPercent;
        return readsFromV2 ? MigrationDatabase.V2 : MigrationDatabase.V1;
    }

    public boolean shouldShadowRead() {
        int shadowReadPercent = migrationSettingsProvider.getSettings().shadowReadPercent();
        return ThreadLocalRandom.current().nextInt(PERCENT) < shadowReadPercent;
    }

    public <T> T useReadDatabase(MigrationDatabase database, ReadQuery<T> query) throws Throwable {
        MigrationDatabase previousDatabase = FIXED_READ_DATABASE.get();
        FIXED_READ_DATABASE.set(database);
        try {
            return query.run();
        } finally {
            if (previousDatabase == null) {
                FIXED_READ_DATABASE.remove();
            } else {
                FIXED_READ_DATABASE.set(previousDatabase);
            }
        }
    }

    private MigrationMode currentMode() {
        return migrationSettingsProvider.getSettings().modeAt(now());
    }

    private OffsetDateTime now() {
        return TimeUtils.toOffsetDateTime(TimeUtils.now());
    }

    @FunctionalInterface
    public interface ReadQuery<T> {

        T run() throws Throwable;
    }
}
