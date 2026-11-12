package com.homes.zipsai.building.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import jakarta.persistence.EntityManager;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import com.homes.zipsai.building.repository.BuildingCoordinateRetryJobRepository;
import com.homes.zipsai.user.domain.User;

@SpringBootTest
@Transactional
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:tc:mysql:8.4.11:///zipsai_coordinate_retry_jobs?TC_DAEMON=true",
    "spring.datasource.username=test",
    "spring.datasource.password=test",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.jpa.hibernate.naming.physical-strategy=org.hibernate.boot.model.naming.PhysicalNamingStrategyStandardImpl",
    "spring.jpa.defer-datasource-initialization=false",
    "spring.flyway.enabled=true"
})
class BuildingCoordinateRetryJobPersistenceTest {

    private static final LocalDateTime NEXT_RETRY = LocalDateTime.of(2026, 10, 16, 12, 0, 0, 123456000);

    @Autowired
    EntityManager entityManager;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    BuildingCoordinateRetryJobRepository buildingCoordinateRetryJobRepository;

    private Building building;

    @BeforeEach
    void setUp() {
        User manager = new User("coordinate-retry@example.com", "password", "관리자", null);
        entityManager.persist(manager);
        building = new Building(manager, "서울시 테스트로 1", "테스트빌");
        entityManager.persist(building);
        entityManager.flush();
    }

    @Test
    @DisplayName("건물을 저장해도 좌표 재시도 작업은 자동 생성되지 않는다")
    void doesNotAutomaticallyCreateJobsForBuildings() {
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM Building_coordinate_retry_jobs", Long.class)).isZero();

        assertThat(entityManager.find(Building.class, building.getId())).isNotNull();
    }

    @Test
    @DisplayName("작업 테이블은 확정된 컬럼 타입과 길이 및 시각 정밀도를 사용한다")
    void usesConfirmedColumnTypes() {
        Map<String, String> types = jdbcTemplate.query("""
            SELECT COLUMN_NAME, COLUMN_TYPE FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'Building_coordinate_retry_jobs'
            """, result -> {
                Map<String, String> columns = new HashMap<>();
                while (result.next()) {
                    columns.put(result.getString(1), result.getString(2));
                }
                return columns;
            });

        assertThat(types).containsExactlyInAnyOrderEntriesOf(Map.of(
            "building_id", "bigint", "status", "varchar(20)", "retry_failure_count", "smallint",
            "claim_version", "bigint", "next_retry_at", "datetime(6)", "lease_expires_at", "datetime(6)",
            "last_error_type", "varchar(30)", "failure_reason", "varchar(30)",
            "created_at", "datetime(6)", "updated_at", "datetime(6)"));
    }

    @Test
    @DisplayName("재시도 실패 횟수와 선점 버전의 DB 기본값은 0이다")
    void defaultsFailureCountAndClaimVersionToZero() {
        insert("PENDING", NEXT_RETRY, null, null, "NETWORK_ERROR");

        assertThat(jdbcTemplate.queryForMap("""
            SELECT retry_failure_count, claim_version, next_retry_at FROM Building_coordinate_retry_jobs
            WHERE building_id = ?
            """, building.getId()))
            .containsEntry("retry_failure_count", 0)

            .containsEntry("claim_version", 0L)

            .containsEntry("next_retry_at", NEXT_RETRY);
    }

    @Test
    @DisplayName("같은 건물의 좌표 재시도 작업은 중복 저장할 수 없다")
    void rejectsDuplicateBuildingJob() {
        insert("PENDING", NEXT_RETRY, null, null, "NETWORK_ERROR");

        assertRejected(() -> insert("PENDING", NEXT_RETRY, null, null, "NETWORK_ERROR"), 1062);
    }

    @Test
    @DisplayName("존재하지 않는 건물의 좌표 재시도 작업은 저장할 수 없다")
    void rejectsMissingBuilding() {
        assertRejected(() -> jdbcTemplate.update("""
            INSERT INTO Building_coordinate_retry_jobs
                (building_id, status, next_retry_at, last_error_type, created_at, updated_at)
            VALUES (?, 'PENDING', ?, 'NETWORK_ERROR', ?, ?)
            """, Long.MAX_VALUE, NEXT_RETRY, NEXT_RETRY, NEXT_RETRY), 1452);
    }

    @ParameterizedTest
    @DisplayName("네 가지 유효한 작업 상태를 저장하고 기본 저장소로 다시 조회할 수 있다")
    @MethodSource("validStates")
    void persistsEveryValidState(String status, LocalDateTime next, LocalDateTime lease, String reason) {
        insert(status, next, lease, reason, "E".repeat(30));

        assertThat(jdbcTemplate.queryForObject("""
            SELECT status FROM Building_coordinate_retry_jobs WHERE building_id = ?
            """, String.class, building.getId())).isEqualTo(status);

        assertThat(buildingCoordinateRetryJobRepository.findById(building.getId()).orElseThrow().getStatus().name())
            .isEqualTo(status);
    }

    @ParameterizedTest
    @DisplayName("미정의 상태값과 상태별 필수 또는 금지 필드 위반은 DB에서 거절한다")
    @MethodSource("invalidStates")
    void rejectsInvalidStateFields(String status, LocalDateTime next, LocalDateTime lease, String reason) {
        assertRejected(() -> insert(status, next, lease, reason, "NETWORK_ERROR"), status == null ? 1048 : 3819);
    }

    @ParameterizedTest
    @DisplayName("작업 컬럼의 범위와 길이 및 필수 값 위반은 DB에서 거절한다")
    @MethodSource("invalidValues")
    void rejectsInvalidColumnValues(String column, Object value) {
        insert("FAILED", null, null, "R".repeat(30), "E".repeat(30));

        assertRejected(() -> jdbcTemplate.update(
            "UPDATE Building_coordinate_retry_jobs SET " + column + " = ? WHERE building_id = ?",
            value, building.getId()), value == null ? 1048 : value instanceof String ? 1406 : 3819);
    }

    @Test
    @DisplayName("제한 재시도 실패 횟수의 최댓값인 3은 저장할 수 있다")
    void acceptsMaximumFailureCount() {
        insert("FAILED", null, null, "RETRY_EXHAUSTED", "NETWORK_ERROR");
        jdbcTemplate.update("""
            UPDATE Building_coordinate_retry_jobs SET retry_failure_count = 3 WHERE building_id = ?
            """, building.getId());

        assertThat(jdbcTemplate.queryForObject("""
            SELECT retry_failure_count FROM Building_coordinate_retry_jobs WHERE building_id = ?
            """, Integer.class, building.getId())).isEqualTo(3);
    }

    @Test
    @DisplayName("작업을 저장하고 다시 조회하면 공유 건물 ID와 초기 필드 및 생성 시각이 보존된다")
    void savesAndReloadsJobWithSharedBuildingIdAndTimestamps() {
        BuildingCoordinateRetryJob job = buildingCoordinateRetryJobRepository.saveAndFlush(
            new BuildingCoordinateRetryJob(building, "NETWORK_ERROR", NEXT_RETRY));
        entityManager.clear();

        BuildingCoordinateRetryJob found = buildingCoordinateRetryJobRepository.findById(building.getId())
            .orElseThrow();

        assertThat(found.getId()).isEqualTo(building.getId());

        assertThat(found.getBuilding().getId()).isEqualTo(building.getId());

        assertThat(found.getStatus()).isEqualTo(BuildingCoordinateRetryJobStatus.PENDING);

        assertThat(found.getRetryFailureCount()).isZero();

        assertThat(found.getClaimVersion()).isZero();

        assertThat(found.getNextRetryAt()).isEqualTo(NEXT_RETRY);

        assertThat(found.getLeaseExpiresAt()).isNull();

        assertThat(found.getLastErrorType()).isEqualTo("NETWORK_ERROR");

        assertThat(found.getFailureReason()).isNull();

        assertThat(found.getCreatedAt()).isNotNull().isEqualTo(job.getCreatedAt());

        assertThat(found.getUpdatedAt()).isEqualTo(found.getCreatedAt());
    }

    @Test
    @DisplayName("기존 DB를 갱신해도 건물 정보는 유지되고 좌표 재시도 작업은 자동 생성되지 않는다")
    void upgradesExistingBuildingsWithoutCreatingJobs() {
        DriverManagerDataSource source = new DriverManagerDataSource(
            "jdbc:tc:mysql:8.4.11:///zipsai_coordinate_retry_upgrade?TC_DAEMON=true", "test", "test");
        Flyway.configure().dataSource(source).target("19").load().migrate();
        JdbcTemplate database = new JdbcTemplate(source);
        database.update("""
            INSERT INTO Users (user_id, email, password, user_status, user_role, created_at, updated_at)
            VALUES (1, 'existing-manager@example.com', 'password', 'ACTIVE', 'MANAGER', NOW(6), NOW(6))
            """);
        database.update("""
            INSERT INTO Buildings (building_id, user_id, road_address, created_at, updated_at)
            VALUES (1, 1, '서울시 기존로 1', NOW(6), NOW(6))
            """);

        Flyway.configure().dataSource(source).load().migrate();

        assertThat(database.queryForObject("SELECT road_address FROM Buildings WHERE building_id = 1", String.class))
            .isEqualTo("서울시 기존로 1");

        assertThat(database.queryForObject("SELECT COUNT(*) FROM Building_coordinate_retry_jobs", Long.class)).isZero();
    }

    private void insert(String status, LocalDateTime next, LocalDateTime lease, String reason, String error) {
        jdbcTemplate.update("""
            INSERT INTO Building_coordinate_retry_jobs
                (building_id, status, next_retry_at, lease_expires_at, failure_reason,
                    last_error_type, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """, building.getId(), status, next, lease, reason, error, NEXT_RETRY, NEXT_RETRY);
    }

    private void assertRejected(Runnable operation, int errorCode) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(DataAccessException.class,
            error -> assertThat(((SQLException) error.getMostSpecificCause()).getErrorCode()).isEqualTo(errorCode));
    }

    static Stream<Arguments> validStates() {
        return Stream.of(
            Arguments.of("PENDING", NEXT_RETRY, null, null),
            Arguments.of("PROCESSING", null, NEXT_RETRY, null),
            Arguments.of("COMPLETED", null, null, null),
            Arguments.of("FAILED", null, null, "R".repeat(30)));
    }

    static Stream<Arguments> invalidStates() {
        return Stream.of(
            Arguments.of("UNKNOWN", null, null, null),
            Arguments.of("pending", NEXT_RETRY, null, null),
            Arguments.of("PENDING ", NEXT_RETRY, null, null),
            Arguments.of(null, NEXT_RETRY, null, null),
            Arguments.of("PENDING", null, null, null),
            Arguments.of("PENDING", NEXT_RETRY, NEXT_RETRY, null),
            Arguments.of("PENDING", NEXT_RETRY, null, "FAILED"),
            Arguments.of("PROCESSING", null, null, null),
            Arguments.of("PROCESSING", NEXT_RETRY, NEXT_RETRY, null),
            Arguments.of("PROCESSING", null, NEXT_RETRY, "FAILED"),
            Arguments.of("COMPLETED", NEXT_RETRY, null, null),
            Arguments.of("COMPLETED", null, NEXT_RETRY, null),
            Arguments.of("COMPLETED", null, null, "FAILED"),
            Arguments.of("FAILED", null, null, null),
            Arguments.of("FAILED", NEXT_RETRY, null, "RETRY_EXHAUSTED"),
            Arguments.of("FAILED", null, NEXT_RETRY, "RETRY_EXHAUSTED"));
    }

    static Stream<Arguments> invalidValues() {
        return Stream.of(
            Arguments.of("retry_failure_count", -1), Arguments.of("retry_failure_count", 4),
            Arguments.of("retry_failure_count", null), Arguments.of("claim_version", -1L),
            Arguments.of("claim_version", null), Arguments.of("last_error_type", "E".repeat(31)),
            Arguments.of("last_error_type", null), Arguments.of("failure_reason", "R".repeat(31)),
            Arguments.of("created_at", null), Arguments.of("updated_at", null));
    }
}
