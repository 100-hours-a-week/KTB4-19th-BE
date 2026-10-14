package com.homes.zipsai.migration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import com.homes.zipsai.global.util.TimeUtils;
import com.homes.zipsai.migration.config.MigrationRoutingConfiguration.MigrationDataSources;
import com.homes.zipsai.migration.domain.MigrationDatabase;
import com.homes.zipsai.migration.domain.ShadowReadComparison;
import com.homes.zipsai.migration.domain.ShadowReadComparison.FieldDifference;
import com.homes.zipsai.migration.domain.ShadowReadQuery;
import com.homes.zipsai.migration.domain.ShadowReadResult;
import com.homes.zipsai.migration.domain.ShadowReadRowCheck;
import com.zaxxer.hikari.HikariDataSource;

@ExtendWith(MockitoExtension.class)
class ShadowReadRowVerifierTest {

    private static final LocalDateTime UPDATED_AT = LocalDateTime.of(2026, 10, 14, 22, 31, 15);
    private static MigrationDataSources migrationDataSources;

    @Mock
    OutboxEntityTables outboxEntityTables;

    ShadowReadRowVerifier shadowReadRowVerifier;

    JdbcClient v1;

    JdbcClient v2;

    @BeforeAll
    static void startDatabases() {
        migrationDataSources = new MigrationDataSources(dataSource("row_check_v1"), dataSource("row_check_v2"));
    }

    @AfterAll
    static void stopDatabases() {
        migrationDataSources.close();
    }

    @BeforeEach
    void setUp() {
        v1 = resetDatabase(migrationDataSources.v1());
        v2 = resetDatabase(migrationDataSources.v2());
        shadowReadRowVerifier = new ShadowReadRowVerifier(migrationDataSources, outboxEntityTables);
    }

    @Test
    @DisplayName("처음에 V1 목록에만 있던 ID가 지금 V2에도 같으면 해소로 본다")
    void resolvesIdThatWasOnlyInV1WhenRowsNowMatch() {
        saveItem(v1, 111783L, "보일러 고장", UPDATED_AT);
        saveItem(v2, 111783L, "보일러 고장", UPDATED_AT);
        saveEvent(v1, 1L, "V1_TO_V2", "111783", "COMPLETED", TimeUtils.now().minusSeconds(20));

        ShadowReadRowCheck rowCheck = verify(onlyInV1("111783"));

        assertThat(rowCheck.resolvedIds()).containsExactly("111783");
        assertThat(rowCheck.result()).isEqualTo(ShadowReadResult.MISMATCH_RESOLVED);
    }

    @Test
    @DisplayName("V2에 아직 없고 PENDING 이벤트가 남아 있으면 반영 대기로 본다")
    void treatsMissingRowWithPendingEventAsLagging() {
        saveItem(v1, 111783L, "보일러 고장", UPDATED_AT);
        saveEvent(v1, 1L, "V1_TO_V2", "111783", "PENDING", null);

        ShadowReadRowCheck rowCheck = verify(onlyInV1("111783"));

        assertThat(rowCheck.laggingIds()).containsExactly("111783");
        assertThat(rowCheck.result()).isEqualTo(ShadowReadResult.MISMATCH_LAGGING);
    }

    @Test
    @DisplayName("재확인 조회를 시작한 뒤에 반영된 이벤트가 있으면 반영 대기로 본다")
    void treatsEventAppliedAfterReadStartedAsLagging() {
        saveItem(v1, 1L, "세탁기", UPDATED_AT.plusSeconds(5));
        saveItem(v2, 1L, "세탁기", UPDATED_AT);
        saveEvent(v1, 1L, "V1_TO_V2", "1", "COMPLETED", TimeUtils.now().plusMinutes(1));

        ShadowReadRowCheck rowCheck = verify(fieldDifferent("1"));

        assertThat(rowCheck.laggingIds()).containsExactly("1");
    }

    @Test
    @DisplayName("V2에서 V1으로 가는 PENDING 이벤트가 있어도 반영 대기로 본다")
    void treatsPendingReverseEventAsLagging() {
        saveItem(v1, 1L, "세탁기", UPDATED_AT);
        saveItem(v2, 1L, "세탁기", UPDATED_AT.plusSeconds(5));
        saveEvent(v2, 1L, "V2_TO_V1", "1", "PENDING", null);

        ShadowReadRowCheck rowCheck = verify(fieldDifferent("1"));

        assertThat(rowCheck.laggingIds()).containsExactly("1");
    }

    @Test
    @DisplayName("행이 다른데 반영할 이벤트가 없으면 지속 불일치로 본다")
    void treatsDifferenceWithoutUnappliedEventAsPersistent() {
        saveItem(v1, 1L, "세탁기", UPDATED_AT.plusSeconds(5));
        saveItem(v2, 1L, "세탁기", UPDATED_AT);
        saveEvent(v1, 1L, "V1_TO_V2", "1", "COMPLETED", TimeUtils.now().minusSeconds(20));

        ShadowReadRowCheck rowCheck = verify(fieldDifferent("1"));

        assertThat(rowCheck.persistentIds()).containsExactly("1");
        assertThat(rowCheck.result()).isEqualTo(ShadowReadResult.MISMATCH_PERSISTENT);
    }

    @Test
    @DisplayName("FAILED 이벤트만 남아 있으면 반영 대기가 아니라 지속 불일치로 본다")
    void treatsFailedEventAsPersistent() {
        saveItem(v1, 1L, "세탁기", UPDATED_AT.plusSeconds(5));
        saveItem(v2, 1L, "세탁기", UPDATED_AT);
        saveEvent(v1, 1L, "V1_TO_V2", "1", "FAILED", null);

        ShadowReadRowCheck rowCheck = verify(fieldDifferent("1"));

        assertThat(rowCheck.persistentIds()).containsExactly("1");
    }

    @Test
    @DisplayName("지속 불일치는 시각 컬럼 값은 그대로, 제목은 해시로 남긴다")
    void masksColumnDifferencesOfPersistentRows() {
        saveItem(v1, 1L, "보일러 고장", UPDATED_AT.plusSeconds(5));
        saveItem(v2, 1L, "누수 문의", UPDATED_AT);

        ShadowReadRowCheck rowCheck = verify(fieldDifferent("1"));

        assertThat(rowCheck.columnDifferences()).contains(
            new FieldDifference("1", "updated_at", "2026-10-14T22:31:20", "2026-10-14T22:31:15"));
        FieldDifference titleDifference = rowCheck.columnDifferences().stream()
            .filter(difference -> difference.field().equals("item_name"))
            .findFirst()
            .orElseThrow();
        assertThat(titleDifference.v1()).startsWith("sha256:").doesNotContain("보일러");
    }

    @Test
    @DisplayName("양쪽 모두 지워진 행은 해소로 본다")
    void resolvesRowDeletedOnBothSides() {
        ShadowReadRowCheck rowCheck = verify(onlyInV1("1"));

        assertThat(rowCheck.resolvedIds()).containsExactly("1");
    }

    @Test
    @DisplayName("ID 필드를 테이블로 찾지 못하면 PK 재확인을 하지 않는다")
    void skipsWhenIdFieldIsNotEntity() {
        given(outboxEntityTables.findTableNameByIdField("attachmentId")).willReturn(Optional.empty());
        ShadowReadComparison comparison = comparison("attachmentId", List.of("1"), List.of());

        Optional<ShadowReadRowCheck> rowCheck = shadowReadRowVerifier.verify(comparison);

        assertThat(rowCheck).isEmpty();
    }

    @Test
    @DisplayName("다른 ID가 없으면 PK 재확인을 하지 않는다")
    void skipsWhenNoEntityIdDiffers() {
        ShadowReadComparison comparison = comparison("itemId", List.of(), List.of());

        Optional<ShadowReadRowCheck> rowCheck = shadowReadRowVerifier.verify(comparison);

        assertThat(rowCheck).isEmpty();
    }

    private ShadowReadRowCheck verify(ShadowReadComparison comparison) {
        given(outboxEntityTables.findTableNameByIdField("itemId")).willReturn(Optional.of("Items"));
        given(outboxEntityTables.getIdColumn("Items")).willReturn("item_id");
        return shadowReadRowVerifier.verify(comparison).orElseThrow();
    }

    private static ShadowReadComparison onlyInV1(String id) {
        return comparison("itemId", List.of(id), List.of());
    }

    private static ShadowReadComparison fieldDifferent(String id) {
        return comparison("itemId", List.of(), List.of(new FieldDifference(id, "updatedAt", "a", "b")));
    }

    private static ShadowReadComparison comparison(String idField, List<String> onlyInV1Ids,
                                                   List<FieldDifference> fieldDifferences) {
        ShadowReadQuery v1Query = new ShadowReadQuery(MigrationDatabase.V1, Instant.now(), Instant.now(), null);
        ShadowReadQuery v2Query = new ShadowReadQuery(MigrationDatabase.V2, Instant.now(), Instant.now(), null);
        return ShadowReadComparison.of(v1Query, v2Query, idField, onlyInV1Ids, List.of(), false, fieldDifferences);
    }

    private static HikariDataSource dataSource(String databaseName) {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl("jdbc:tc:mysql:8.4.11:///" + databaseName + "?TC_DAEMON=true");
        dataSource.setUsername("test");
        dataSource.setPassword("test");
        return dataSource;
    }

    private static JdbcClient resetDatabase(HikariDataSource dataSource) {
        JdbcClient jdbcClient = JdbcClient.create(dataSource);
        for (String table : List.of("Migration_outbox_events", "Migration_applied_events", "Items")) {
            jdbcClient.sql("DROP TABLE IF EXISTS " + table).update();
        }
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V19__migration_outbox_events.sql"))
            .execute(dataSource);
        jdbcClient.sql("""
                CREATE TABLE Items (
                    item_id BIGINT NOT NULL PRIMARY KEY,
                    item_name VARCHAR(20) NOT NULL,
                    updated_at DATETIME(6) NOT NULL
                )
                """)
            .update();
        return jdbcClient;
    }

    private static void saveItem(JdbcClient database, Long itemId, String itemName, LocalDateTime updatedAt) {
        database.sql("INSERT INTO Items (item_id, item_name, updated_at) VALUES (?, ?, ?)")
            .params(itemId, itemName, updatedAt)
            .update();
    }

    private static void saveEvent(JdbcClient database, Long eventId, String direction, String entityId,
                                  String status, LocalDateTime processedAt) {
        database.sql("""
                INSERT INTO Migration_outbox_events (event_id, direction, entity_type, entity_id, operation, status,
                    retry_count, created_at, processed_at)
                VALUES (?, ?, 'Items', ?, 'UPDATE', ?, 0, ?, ?)
                """)
            .params(eventId, direction, entityId, status, UPDATED_AT, processedAt)
            .update();
    }
}
