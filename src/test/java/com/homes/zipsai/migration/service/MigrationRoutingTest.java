package com.homes.zipsai.migration.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;

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
    @DisplayName("전환 시각 전에는 이전 mode의 DB에 쓴다")
    void writesToPreviousDatabaseBeforeSwitchTime() {
        migrationSettingsProvider.update(new MigrationSettings(MigrationMode.V2_PRIMARY,
            OffsetDateTime.now().plusDays(1), WorkerMode.PAUSED, false, false, 0, 0));

        MigrationDatabase database = migrationRouting.determineWriteDatabase();

        assertThat(database).isEqualTo(MigrationDatabase.V1);
    }

    @Test
    @DisplayName("전환 시각이 지나면 지정한 mode의 DB에 쓴다")
    void writesToNewDatabaseAfterSwitchTime() {
        migrationSettingsProvider.update(new MigrationSettings(MigrationMode.V2_PRIMARY,
            OffsetDateTime.now().minusDays(1), WorkerMode.PAUSED, false, false, 0, 0));

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

    @Test
    @DisplayName("V2 읽기 비율이 0이면 V1에서 읽는다")
    void readsFromV1WhenReadPercentIsZero() {
        givenSettings(MigrationMode.V1_PRIMARY, 0);

        MigrationDatabase database = migrationRouting.determineReadDatabase();

        assertThat(database).isEqualTo(MigrationDatabase.V1);
    }

    @Test
    @DisplayName("V2 읽기 비율이 100이면 V2에서 읽는다")
    void readsFromV2WhenReadPercentIsHundred() {
        givenSettings(MigrationMode.V1_PRIMARY, 100);

        MigrationDatabase database = migrationRouting.determineReadDatabase();

        assertThat(database).isEqualTo(MigrationDatabase.V2);
    }

    @Test
    @DisplayName("V2_PRIMARY면 읽기 비율과 관계없이 V2에서 읽는다")
    void readsFromV2WhenV2IsPrimary() {
        givenSettings(MigrationMode.V2_PRIMARY, 0);

        MigrationDatabase database = migrationRouting.determineReadDatabase();

        assertThat(database).isEqualTo(MigrationDatabase.V2);
    }

    @Test
    @DisplayName("읽을 DB를 정해 두면 그 안의 조회는 그 DB에서 읽는다")
    void readsFromFixedDatabase() throws Throwable {
        givenSettings(MigrationMode.V1_PRIMARY, 100);

        MigrationDatabase database = migrationRouting.useReadDatabase(MigrationDatabase.V1,
            migrationRouting::determineReadDatabase);

        assertThat(database).isEqualTo(MigrationDatabase.V1);
    }

    @Test
    @DisplayName("Shadow Read 비율이 0이면 비교하지 않는다")
    void skipsShadowReadWhenPercentIsZero() {
        givenShadowReadPercent(0);

        boolean shadowRead = migrationRouting.shouldShadowRead();

        assertThat(shadowRead).isFalse();
    }

    @Test
    @DisplayName("Shadow Read 비율이 100이면 모든 조회를 비교한다")
    void comparesEveryReadWhenPercentIsHundred() {
        givenShadowReadPercent(100);

        boolean shadowRead = migrationRouting.shouldShadowRead();

        assertThat(shadowRead).isTrue();
    }

    private void givenSettings(MigrationMode mode, int v2ReadPercent) {
        migrationSettingsProvider.update(
            new MigrationSettings(mode, null, WorkerMode.PAUSED, true, true, v2ReadPercent, 0));
    }

    private void givenShadowReadPercent(int shadowReadPercent) {
        migrationSettingsProvider.update(
            new MigrationSettings(MigrationMode.V1_PRIMARY, null, WorkerMode.PAUSED, true, true, 0, shadowReadPercent));
    }

    private void completeTransaction() {
        for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
        }
        TransactionSynchronizationManager.clearSynchronization();
    }
}
