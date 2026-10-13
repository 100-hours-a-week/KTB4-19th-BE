package com.homes.zipsai.migration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import java.time.LocalDateTime;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import com.homes.zipsai.global.util.TimeUtils;
import com.homes.zipsai.migration.config.MigrationProperties;
import com.homes.zipsai.migration.config.OutboxWorkerConfiguration;
import com.homes.zipsai.migration.domain.MigrationMode;
import com.homes.zipsai.migration.domain.MigrationSettings;
import com.homes.zipsai.migration.domain.WorkerMode;

@SpringJUnitConfig(OutboxRelayServiceTest.WorkerTestConfiguration.class)
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:tc:mysql:8.4.11:///migration_v1?TC_DAEMON=true",
    "spring.datasource.username=test",
    "spring.datasource.password=test",
    "app.migration.worker-enabled=true",
    "app.migration.v2-datasource.url=jdbc:tc:mysql:8.4.11:///migration_v2?TC_DAEMON=true",
    "app.migration.v2-datasource.username=test",
    "app.migration.v2-datasource.password=test"
})
class OutboxRelayServiceTest {

    private static final LocalDateTime NOON = LocalDateTime.of(2026, 10, 13, 12, 0);
    private static final MigrationSettings V1_PRIMARY_RUNNING =
        new MigrationSettings(MigrationMode.V1_PRIMARY, null, WorkerMode.RUNNING, true, true, 0, false);

    @Configuration
    @EnableTransactionManagement
    @EnableConfigurationProperties({DataSourceProperties.class, MigrationProperties.class})
    @Import({OutboxWorkerConfiguration.class, OutboxApplyService.class, OutboxRelayService.class,
        MigrationSettingsProvider.class})
    static class WorkerTestConfiguration {
    }

    @MockitoBean
    OutboxEntityTables outboxEntityTables;

    @Autowired
    OutboxRelayService outboxRelayService;

    @Autowired
    MigrationSettingsProvider migrationSettingsProvider;

    @Autowired
    @Qualifier("outboxWorkerV1DataSource")
    DataSource outboxWorkerV1DataSource;

    @Autowired
    @Qualifier("outboxWorkerV2DataSource")
    DataSource outboxWorkerV2DataSource;

    JdbcClient v1;

    JdbcClient v2;

    @BeforeEach
    void setUp() {
        v1 = resetDatabase(outboxWorkerV1DataSource);
        v2 = resetDatabase(outboxWorkerV2DataSource);
        given(outboxEntityTables.getIdColumn("Items")).willReturn("item_id");
        migrationSettingsProvider.update(V1_PRIMARY_RUNNING);
    }

    @AfterEach
    void tearDown() {
        migrationSettingsProvider.update(MigrationSettings.DEFAULT);
    }

    @Test
    @DisplayName("원본 행을 대상 DB에 새로 추가한다")
    void insertsSourceRowIntoTarget() {
        saveItem(v1, 1L, "세탁기");
        saveEvent(v1, 10L, "V1_TO_V2", "Items", "1");

        outboxRelayService.relayPendingEvents(NOON);

        assertThat(itemName(v2, 1L)).containsExactly("세탁기");
    }

    @Test
    @DisplayName("대상 DB에 있는 행은 원본의 최신 값으로 덮어쓴다")
    void overwritesTargetRowWithLatestSourceValue() {
        saveItem(v1, 1L, "건조기");
        saveItem(v2, 1L, "세탁기");
        saveEvent(v1, 10L, "V1_TO_V2", "Items", "1");

        outboxRelayService.relayPendingEvents(NOON);

        assertThat(itemName(v2, 1L)).containsExactly("건조기");
    }

    @Test
    @DisplayName("원본에 행이 없으면 대상 DB에서도 지운다")
    void deletesTargetRowWhenSourceRowIsMissing() {
        saveItem(v2, 1L, "세탁기");
        saveEvent(v1, 10L, "V1_TO_V2", "Items", "1");

        outboxRelayService.relayPendingEvents(NOON);

        assertThat(itemName(v2, 1L)).isEmpty();
    }

    @Test
    @DisplayName("반영한 이벤트는 원본에서 완료로 바꾸고 대상 DB 적용 이력에 남긴다")
    void marksEventCompletedAndRecordsAppliedEventInTarget() {
        saveItem(v1, 1L, "세탁기");
        saveEvent(v1, 10L, "V1_TO_V2", "Items", "1");

        outboxRelayService.relayPendingEvents(NOON);

        assertThat(eventStatus(v1, 10L)).isEqualTo("COMPLETED");
        assertThat(appliedEventCount(v2, "V1_TO_V2", 10L)).isEqualTo(1L);
    }

    @Test
    @DisplayName("대상 DB 적용 이력에 있으면 다시 반영하지 않고 완료로 바꾼다")
    void skipsEventAlreadyApplied() {
        saveItem(v1, 1L, "건조기");
        saveItem(v2, 1L, "세탁기");
        saveEvent(v1, 10L, "V1_TO_V2", "Items", "1");
        markApplied(v2, "V1_TO_V2", 10L);

        outboxRelayService.relayPendingEvents(NOON);

        assertThat(itemName(v2, 1L)).containsExactly("세탁기");
        assertThat(eventStatus(v1, 10L)).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("GTID 복제로 대상 DB에 넘어간 이벤트는 다시 반영하지 않는다")
    void skipsEventReplicatedByGtid() {
        saveItem(v1, 1L, "건조기");
        saveItem(v2, 1L, "세탁기");
        saveEvent(v1, 10L, "V1_TO_V2", "Items", "1");
        saveEvent(v2, 10L, "V1_TO_V2", "Items", "1");

        outboxRelayService.relayPendingEvents(NOON);

        assertThat(itemName(v2, 1L)).containsExactly("세탁기");
    }

    @Test
    @DisplayName("번호가 같아도 방향이 다른 대상 DB 이벤트는 다른 이벤트로 보고 반영한다")
    void appliesEventWhenTargetHasSameNumberInOtherDirection() {
        saveItem(v1, 1L, "건조기");
        saveEvent(v1, 10L, "V1_TO_V2", "Items", "1");
        saveEvent(v2, 10L, "V2_TO_V1", "Items", "2", "COMPLETED", 0, null);
        markProcessed(v2, 10L);

        outboxRelayService.relayPendingEvents(NOON);

        assertThat(itemName(v2, 1L)).containsExactly("건조기");
    }

    @Test
    @DisplayName("반영에 실패하면 재시도 횟수와 다음 시도 시각을 남긴다")
    void recordsRetryWhenRelayFails() {
        given(outboxEntityTables.getIdColumn("Unknown")).willThrow(new IllegalArgumentException("unknown"));
        saveEvent(v1, 10L, "V1_TO_V2", "Unknown", "1");

        outboxRelayService.relayPendingEvents(NOON);

        assertThat(retryOf(v1, 10L)).isEqualTo(new Retry("PENDING", 1, NOON.plusSeconds(10)));
    }

    @Test
    @DisplayName("반영에 실패하면 뒤의 이벤트를 먼저 반영하지 않는다")
    void stopsAtFailedEvent() {
        given(outboxEntityTables.getIdColumn("Unknown")).willThrow(new IllegalArgumentException("unknown"));
        saveEvent(v1, 10L, "V1_TO_V2", "Unknown", "1");
        saveItem(v1, 1L, "세탁기");
        saveEvent(v1, 11L, "V1_TO_V2", "Items", "1");

        outboxRelayService.relayPendingEvents(NOON);

        assertThat(itemName(v2, 1L)).isEmpty();
        assertThat(eventStatus(v1, 11L)).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("다섯 번째로 실패하면 FAILED로 바꾼다")
    void marksEventFailedAfterFifthFailure() {
        given(outboxEntityTables.getIdColumn("Unknown")).willThrow(new IllegalArgumentException("unknown"));
        saveEvent(v1, 10L, "V1_TO_V2", "Unknown", "1", "PENDING", 4, null);

        outboxRelayService.relayPendingEvents(NOON);

        assertThat(retryOf(v1, 10L).status()).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("FAILED 이벤트가 남아 있으면 뒤의 이벤트를 반영하지 않는다")
    void staysStoppedWhileFailedEventRemains() {
        saveEvent(v1, 10L, "V1_TO_V2", "Items", "2", "FAILED", 5, NOON.minusMinutes(1));
        saveItem(v1, 1L, "세탁기");
        saveEvent(v1, 11L, "V1_TO_V2", "Items", "1");

        outboxRelayService.relayPendingEvents(NOON);

        assertThat(itemName(v2, 1L)).isEmpty();
    }

    @Test
    @DisplayName("다음 시도 시각 전이면 그 이벤트와 뒤의 이벤트를 기다린다")
    void waitsUntilNextRetryTime() {
        saveItem(v1, 1L, "세탁기");
        saveEvent(v1, 10L, "V1_TO_V2", "Items", "1", "PENDING", 1, NOON.plusSeconds(1));
        saveItem(v1, 2L, "건조기");
        saveEvent(v1, 11L, "V1_TO_V2", "Items", "2");

        outboxRelayService.relayPendingEvents(NOON);

        assertThat(itemName(v2, 1L)).isEmpty();
        assertThat(itemName(v2, 2L)).isEmpty();
    }

    @Test
    @DisplayName("V2가 기준 DB면 V2의 이벤트를 읽어 V1에 반영한다")
    void relaysV2EventsToV1WhenV2IsPrimary() {
        migrationSettingsProvider.update(
            new MigrationSettings(MigrationMode.V2_PRIMARY, null, WorkerMode.RUNNING, true, true, 0, false));
        saveItem(v2, 1L, "세탁기");
        saveEvent(v2, 10L, "V2_TO_V1", "Items", "1");

        outboxRelayService.relayPendingEvents(NOON);

        assertThat(itemName(v1, 1L)).containsExactly("세탁기");
    }

    @Test
    @DisplayName("V2_PRIMARY로 바뀌면 V1에 남은 이벤트부터 V2에 반영한다")
    void relaysLeftoverV1EventsFirstAfterSwitchingToV2() {
        migrationSettingsProvider.update(
            new MigrationSettings(MigrationMode.V2_PRIMARY, null, WorkerMode.RUNNING, true, true, 0, false));
        saveItem(v1, 1L, "세탁기");
        saveEvent(v1, 10L, "V1_TO_V2", "Items", "1");

        outboxRelayService.relayPendingEvents(NOON);

        assertThat(itemName(v2, 1L)).containsExactly("세탁기");
    }

    @Test
    @DisplayName("V1에 남은 이벤트가 있으면 V2의 이벤트는 다음 차례로 미룬다")
    void waitsV2EventsWhileV1EventsRemain() {
        migrationSettingsProvider.update(
            new MigrationSettings(MigrationMode.V2_PRIMARY, null, WorkerMode.RUNNING, true, true, 0, false));
        saveItem(v1, 1L, "세탁기");
        saveEvent(v1, 10L, "V1_TO_V2", "Items", "1");
        saveItem(v2, 2L, "건조기");
        saveEvent(v2, 20L, "V2_TO_V1", "Items", "2");

        outboxRelayService.relayPendingEvents(NOON);

        assertThat(itemName(v1, 2L)).isEmpty();
        assertThat(eventStatus(v2, 20L)).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("처리 방향과 다른 이벤트는 처리하지 않는다")
    void ignoresEventsOfOtherDirection() {
        saveItem(v1, 1L, "세탁기");
        saveEvent(v1, 10L, "V2_TO_V1", "Items", "1");

        outboxRelayService.relayPendingEvents(NOON);

        assertThat(eventStatus(v1, 10L)).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("워커가 PAUSED면 이벤트를 처리하지 않는다")
    void doesNothingWhenWorkerIsPaused() {
        migrationSettingsProvider.update(
            new MigrationSettings(MigrationMode.V1_PRIMARY, null, WorkerMode.PAUSED, true, true, 0, false));
        saveItem(v1, 1L, "세탁기");
        saveEvent(v1, 10L, "V1_TO_V2", "Items", "1");

        outboxRelayService.relayPendingEvents(NOON);

        assertThat(eventStatus(v1, 10L)).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("처리 중 DRAINING으로 바뀌면 처리 중인 이벤트만 끝내고 멈춘다")
    void finishesCurrentEventAndStopsWhenDraining() {
        given(outboxEntityTables.getIdColumn("Items")).willAnswer(invocation -> {
            migrationSettingsProvider.update(
                new MigrationSettings(MigrationMode.V1_PRIMARY, null, WorkerMode.DRAINING, true, true, 0, false));
            return "item_id";
        });
        saveItem(v1, 1L, "세탁기");
        saveEvent(v1, 10L, "V1_TO_V2", "Items", "1");
        saveItem(v1, 2L, "건조기");
        saveEvent(v1, 11L, "V1_TO_V2", "Items", "2");

        outboxRelayService.relayPendingEvents(NOON);

        assertThat(eventStatus(v1, 10L)).isEqualTo("COMPLETED");
        assertThat(eventStatus(v1, 11L)).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("정방향 워커가 꺼져 있으면 V1 이벤트를 처리하지 않는다")
    void skipsForwardEventsWhenForwardWorkerIsDisabled() {
        migrationSettingsProvider.update(settings(MigrationMode.V1_PRIMARY, false, true));
        saveItem(v1, 1L, "세탁기");
        saveEvent(v1, 10L, "V1_TO_V2", "Items", "1");

        outboxRelayService.relayPendingEvents(NOON);

        assertThat(eventStatus(v1, 10L)).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("정방향 워커가 꺼져 있으면 V1에 남은 이벤트를 기다리지 않고 V2 이벤트를 처리한다")
    void relaysV2EventsWhenForwardWorkerIsDisabled() {
        migrationSettingsProvider.update(settings(MigrationMode.V2_PRIMARY, false, true));
        saveItem(v1, 1L, "세탁기");
        saveEvent(v1, 10L, "V1_TO_V2", "Items", "1");
        saveItem(v2, 2L, "건조기");
        saveEvent(v2, 20L, "V2_TO_V1", "Items", "2");

        outboxRelayService.relayPendingEvents(NOON);

        assertThat(itemName(v1, 2L)).containsExactly("건조기");
        assertThat(eventStatus(v1, 10L)).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("전환 시각 전에는 이전 mode 기준으로 처리한다")
    void relaysByPreviousModeBeforeSwitchTime() {
        migrationSettingsProvider.update(new MigrationSettings(MigrationMode.V2_PRIMARY,
            TimeUtils.toOffsetDateTime(NOON.plusMinutes(1)), WorkerMode.RUNNING, true, false, 0, false));
        saveItem(v1, 1L, "세탁기");
        saveEvent(v1, 10L, "V1_TO_V2", "Items", "1");

        outboxRelayService.relayPendingEvents(NOON);

        assertThat(itemName(v2, 1L)).containsExactly("세탁기");
    }

    @Test
    @DisplayName("FAILED 이벤트를 PENDING으로 되돌리면 다음 회차에 다시 처리한다")
    void relaysFailedEventAfterResetToPending() {
        saveItem(v1, 1L, "세탁기");
        saveEvent(v1, 10L, "V1_TO_V2", "Items", "1", "FAILED", 5, NOON.minusMinutes(1));
        v1.sql("""
                UPDATE Migration_outbox_events
                SET status = 'PENDING', retry_count = 0, next_retry_at = NULL, last_error = NULL
                WHERE event_id = 10
                """)
            .update();

        outboxRelayService.relayPendingEvents(NOON);

        assertThat(itemName(v2, 1L)).containsExactly("세탁기");
    }

    @Test
    @DisplayName("참조하는 행이 아직 없어도 대상 DB에 반영한다")
    void appliesRowWithoutReferencedRow() {
        given(outboxEntityTables.getIdColumn("Item_parts")).willReturn("part_id");
        v1.sql("INSERT INTO Item_parts (part_id, item_id) VALUES (1, 99)").update();
        saveEvent(v1, 10L, "V1_TO_V2", "Item_parts", "1");

        outboxRelayService.relayPendingEvents(NOON);

        assertThat(v2.sql("SELECT item_id FROM Item_parts WHERE part_id = 1").query(Long.class).list())
            .containsExactly(99L);
    }

    @Test
    @DisplayName("예약어 이름의 컬럼도 반영한다")
    void appliesReservedWordColumn() {
        given(outboxEntityTables.getIdColumn("Item_keys")).willReturn("key_id");
        v1.sql("INSERT INTO Item_keys (key_id, `key`) VALUES (1, 'door')").update();
        saveEvent(v1, 10L, "V1_TO_V2", "Item_keys", "1");

        outboxRelayService.relayPendingEvents(NOON);

        assertThat(v2.sql("SELECT `key` FROM Item_keys WHERE key_id = 1").query(String.class).list())
            .containsExactly("door");
    }

    @Test
    @DisplayName("좌표 컬럼도 같은 값으로 반영한다")
    void appliesPointColumn() {
        given(outboxEntityTables.getIdColumn("Item_locations")).willReturn("item_id");
        v1.sql("INSERT INTO Item_locations (item_id, location) VALUES (1, ST_GeomFromText('POINT(37.5 127.0)', 4326))")
            .update();
        saveEvent(v1, 10L, "V1_TO_V2", "Item_locations", "1");

        outboxRelayService.relayPendingEvents(NOON);

        assertThat(v2.sql("SELECT ST_AsText(location) FROM Item_locations WHERE item_id = 1")
            .query(String.class)
            .list()).containsExactly("POINT(37.5 127)");
    }

    private JdbcClient resetDatabase(DataSource dataSource) {
        JdbcClient jdbcClient = JdbcClient.create(dataSource);
        for (String table : List.of("Migration_outbox_events", "Migration_applied_events", "Item_parts", "Items",
            "Item_keys", "Item_locations")) {
            jdbcClient.sql("DROP TABLE IF EXISTS " + table).update();
        }
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V19__migration_outbox_events.sql"))
            .execute(dataSource);
        jdbcClient.sql("CREATE TABLE Items (item_id BIGINT NOT NULL PRIMARY KEY, item_name VARCHAR(20) NOT NULL)")
            .update();
        jdbcClient.sql("""
                CREATE TABLE Item_parts (
                    part_id BIGINT NOT NULL PRIMARY KEY,
                    item_id BIGINT NOT NULL,
                    CONSTRAINT fk_item_parts_item FOREIGN KEY (item_id) REFERENCES Items (item_id)
                )
                """)
            .update();
        jdbcClient.sql("CREATE TABLE Item_keys (key_id BIGINT NOT NULL PRIMARY KEY, `key` VARCHAR(20) NOT NULL)")
            .update();
        jdbcClient.sql("""
                CREATE TABLE Item_locations (
                    item_id BIGINT NOT NULL PRIMARY KEY,
                    location POINT NOT NULL SRID 4326
                )
                """)
            .update();
        return jdbcClient;
    }

    private void saveItem(JdbcClient database, Long itemId, String itemName) {
        database.sql("INSERT INTO Items (item_id, item_name) VALUES (?, ?)")
            .params(itemId, itemName)
            .update();
    }

    private void saveEvent(JdbcClient database, Long eventId, String direction, String entityType, String entityId) {
        saveEvent(database, eventId, direction, entityType, entityId, "PENDING", 0, null);
    }

    private void saveEvent(JdbcClient database, Long eventId, String direction, String entityType, String entityId,
                           String status, int retryCount, LocalDateTime nextRetryAt) {
        database.sql("""
                INSERT INTO Migration_outbox_events (event_id, direction, entity_type, entity_id, operation, status,
                    retry_count, next_retry_at, created_at)
                VALUES (?, ?, ?, ?, 'UPDATE', ?, ?, ?, ?)
                """)
            .params(eventId, direction, entityType, entityId, status, retryCount, nextRetryAt, NOON.minusMinutes(1))
            .update();
    }

    private void markProcessed(JdbcClient database, Long eventId) {
        database.sql("UPDATE Migration_outbox_events SET processed_at = ? WHERE event_id = ?")
            .params(NOON.minusMinutes(1), eventId)
            .update();
    }

    private MigrationSettings settings(MigrationMode mode, boolean forwardWorkerEnabled, boolean reverseWorkerEnabled) {
        return new MigrationSettings(mode, null, WorkerMode.RUNNING, forwardWorkerEnabled, reverseWorkerEnabled, 0,
            false);
    }

    private List<String> itemName(JdbcClient database, Long itemId) {
        return database.sql("SELECT item_name FROM Items WHERE item_id = ?")
            .param(itemId)
            .query(String.class)
            .list();
    }

    private String eventStatus(JdbcClient database, Long eventId) {
        return database.sql("SELECT status FROM Migration_outbox_events WHERE event_id = ?")
            .param(eventId)
            .query(String.class)
            .single();
    }

    private void markApplied(JdbcClient database, String direction, Long eventId) {
        database.sql("INSERT INTO Migration_applied_events (direction, event_id, applied_at) VALUES (?, ?, ?)")
            .params(direction, eventId, NOON.minusMinutes(1))
            .update();
    }

    private Long appliedEventCount(JdbcClient database, String direction, Long eventId) {
        return database.sql("SELECT COUNT(*) FROM Migration_applied_events WHERE direction = ? AND event_id = ?")
            .params(direction, eventId)
            .query(Long.class)
            .single();
    }

    private Retry retryOf(JdbcClient database, Long eventId) {
        return database.sql("SELECT status, retry_count, next_retry_at FROM Migration_outbox_events WHERE event_id = ?")
            .param(eventId)
            .query(Retry.class)
            .single();
    }

    record Retry(String status, int retryCount, LocalDateTime nextRetryAt) {
    }
}
