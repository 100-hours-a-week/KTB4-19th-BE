package com.homes.zipsai.migration.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.homes.zipsai.migration.domain.MigrationDatabase;
import com.homes.zipsai.migration.domain.MigrationMode;
import com.homes.zipsai.migration.domain.MigrationSettings;
import com.homes.zipsai.migration.domain.WorkerMode;

class MigrationRoutingTest {

    MigrationSettingsProvider migrationSettingsProvider;

    MigrationRouting migrationRouting;

    @BeforeEach
    void setUp() {
        migrationSettingsProvider = new MigrationSettingsProvider();
        migrationRouting = new MigrationRouting(migrationSettingsProvider);
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            completeTransaction();
        }
    }

    @Test
    @DisplayName("V1_PRIMARY면 V1에 쓴다")
    void writesToV1WhenV1IsPrimary() {
        givenSettings(MigrationMode.V1_PRIMARY, 0);

        MigrationDatabase database = migrationRouting.determineWriteDatabase();

        assertThat(database).isEqualTo(MigrationDatabase.V1);
    }

    @Test
    @DisplayName("V2_PRIMARY면 V2에 쓴다")
    void writesToV2WhenV2IsPrimary() {
        givenSettings(MigrationMode.V2_PRIMARY, 0);

        MigrationDatabase database = migrationRouting.determineWriteDatabase();

        assertThat(database).isEqualTo(MigrationDatabase.V2);
    }

    @Test
    @DisplayName("트랜잭션 중에 mode가 바뀌어도 처음 고른 DB에 쓴다")
    void keepsWriteDatabaseDuringTransaction() {
        TransactionSynchronizationManager.initSynchronization();
        givenSettings(MigrationMode.V1_PRIMARY, 0);
        migrationRouting.determineWriteDatabase();
        givenSettings(MigrationMode.V2_PRIMARY, 0);

        MigrationDatabase database = migrationRouting.determineWriteDatabase();

        assertThat(database).isEqualTo(MigrationDatabase.V1);
    }

    @Test
    @DisplayName("트랜잭션이 끝나면 다음 트랜잭션은 바뀐 mode의 DB에 쓴다")
    void choosesNewWriteDatabaseAfterTransaction() {
        TransactionSynchronizationManager.initSynchronization();
        givenSettings(MigrationMode.V1_PRIMARY, 0);
        migrationRouting.determineWriteDatabase();
        completeTransaction();
        givenSettings(MigrationMode.V2_PRIMARY, 0);
        TransactionSynchronizationManager.initSynchronization();

        MigrationDatabase database = migrationRouting.determineWriteDatabase();

        assertThat(database).isEqualTo(MigrationDatabase.V2);
    }

    private void givenSettings(MigrationMode mode, int v2ReadPercent) {
        migrationSettingsProvider.update(
            new MigrationSettings(mode, null, WorkerMode.PAUSED, true, true, v2ReadPercent, false));
    }

    private void completeTransaction() {
        for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
        }
        TransactionSynchronizationManager.clearSynchronization();
    }
}
