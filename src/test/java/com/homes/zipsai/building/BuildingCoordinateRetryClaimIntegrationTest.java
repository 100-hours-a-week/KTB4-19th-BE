package com.homes.zipsai.building;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import com.homes.zipsai.building.domain.Building;
import com.homes.zipsai.building.domain.BuildingCoordinateRetryJob;
import com.homes.zipsai.building.domain.BuildingCoordinateRetryJobStatus;
import com.homes.zipsai.building.repository.BuildingCoordinateRetryJobRepository;
import com.homes.zipsai.building.service.BuildingCoordinateRetryClaimService;
import com.homes.zipsai.building.service.BuildingCoordinateRetryClaimService.Claim;
import com.homes.zipsai.user.domain.User;

@SpringBootTest
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:tc:mysql:8.4.11:///zipsai_coordinate_retry_claim?TC_DAEMON=true",
    "spring.datasource.username=test",
    "spring.datasource.password=test",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.jpa.hibernate.naming.physical-strategy=org.hibernate.boot.model.naming.PhysicalNamingStrategyStandardImpl",
    "spring.jpa.defer-datasource-initialization=false",
    "spring.flyway.enabled=true"
})
class BuildingCoordinateRetryClaimIntegrationTest {

    private static final String ADDRESS = "서울 강남구 테헤란로 123";

    @Autowired
    BuildingCoordinateRetryClaimService buildingCoordinateRetryClaimService;

    @Autowired
    BuildingCoordinateRetryJobRepository buildingCoordinateRetryJobRepository;

    @Autowired
    EntityManager entityManager;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    DataSource dataSource;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM Building_coordinate_retry_jobs");
        jdbcTemplate.update("DELETE FROM Building_locations");
    }

    @Test
    @DisplayName("실행 가능한 작업이 없으면 빈 선점 결과를 반환한다")
    void returnsEmptyWhenNoJobsExist() {
        assertThat(buildingCoordinateRetryClaimService.claimNext()).isEmpty();
    }

    @Test
    @DisplayName("실행 시각이 된 작업 하나를 선점하고 횟수와 오류를 보존한다")
    void claimsOneDueJob() {
        Long id = pendingJob((short) 2);
        LocalDateTime before = databaseTime();

        Optional<Claim> result = buildingCoordinateRetryClaimService.claimNext();

        assertThat(result).isPresent();

        Claim claim = result.orElseThrow();
        assertThat(claim.buildingId()).isEqualTo(id);

        assertThat(claim.version()).isEqualTo(1);

        assertThat(claim.roadAddress()).isEqualTo(ADDRESS);

        assertThat(claim.retryFailureCount()).isEqualTo((short) 2);

        BuildingCoordinateRetryJob saved = job(id);
        assertThat(saved.getStatus()).isEqualTo(BuildingCoordinateRetryJobStatus.PROCESSING);

        assertThat(saved.getNextRetryAt()).isNull();

        assertThat(saved.getLeaseExpiresAt()).isBetween(before.plusMinutes(10), databaseTime().plusMinutes(10));

        assertThat(saved.getRetryFailureCount()).isEqualTo((short) 2);

        assertThat(saved.getLastErrorType()).isEqualTo("NETWORK_ERROR");

        assertThat(saved.getFailureReason()).isNull();

        assertThat(buildingCoordinateRetryClaimService.claimNext()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "PROCESSING", "COMPLETED", "FAILED"})
    @DisplayName("미래 대기 작업과 유효한 선점 및 종료된 작업은 선점하지 않는다")
    void skipsIneligibleJobs(String status) {
        Long id = pendingJob((short) 0);
        jdbcTemplate.update("""
            UPDATE Building_coordinate_retry_jobs SET status = ?,
                next_retry_at = ?, lease_expires_at = ?, failure_reason = ? WHERE building_id = ?
            """, status, "PENDING".equals(status) ? databaseTime().plusHours(1) : null,
            "PROCESSING".equals(status) ? databaseTime().plusMinutes(10) : null,
            "FAILED".equals(status) ? "UNKNOWN_ERROR" : null, id);

        assertThat(buildingCoordinateRetryClaimService.claimNext()).isEmpty();

        assertThat(job(id).getStatus().name()).isEqualTo(status);
    }

    @Test
    @DisplayName("만료된 선점은 버전을 증가시켜 회수하고 실패 횟수는 소비하지 않는다")
    void reclaimsExpiredJob() {
        Claim old = processingJob((short) 2);
        expire(old.buildingId());

        Optional<Claim> result = buildingCoordinateRetryClaimService.claimNext();

        assertThat(result).isPresent();

        Claim renewed = result.orElseThrow();
        assertThat(renewed.buildingId()).isEqualTo(old.buildingId());

        assertThat(renewed.version()).isEqualTo(old.version() + 1);

        assertThat(renewed.retryFailureCount()).isEqualTo((short) 2);

        assertThat(job(old.buildingId()).getLeaseExpiresAt()).isAfter(databaseTime());
    }

    @Test
    @DisplayName("독립 연결에서 이미 잠긴 작업은 대기하지 않고 다음 작업을 선점한다")
    void skipsLockedJob() throws Exception {
        Long first = pendingJob((short) 0);
        Long second = pendingJob((short) 0);
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            lock(connection, first);
            try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
                Future<Optional<Claim>> result = executor.submit(buildingCoordinateRetryClaimService::claimNext);
                try {
                    assertThat(result.get(5, TimeUnit.SECONDS)).map(Claim::buildingId).contains(second);
                } finally {
                    connection.rollback();
                }
            }
        }

        assertThat(job(first).getStatus()).isEqualTo(BuildingCoordinateRetryJobStatus.PENDING);
    }

    @Test
    @DisplayName("두 인스턴스가 동시에 선점해도 같은 작업을 중복 배정하지 않는다")
    void claimsOnceAcrossConcurrentWorkers() throws Exception {
        Long id = pendingJob((short) 0);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Optional<Claim>> first = executor.submit(() -> {
                start.await();
                return buildingCoordinateRetryClaimService.claimNext();
            });
            Future<Optional<Claim>> second = executor.submit(() -> {
                start.await();
                return buildingCoordinateRetryClaimService.claimNext();
            });
            start.countDown();
            List<Claim> results = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS))
                .stream().flatMap(Optional::stream).toList();

            assertThat(results).extracting(Claim::buildingId).containsExactly(id);
        }

        assertThat(job(id).getClaimVersion()).isEqualTo(1);
    }

    @Test
    @DisplayName("선점 메서드가 반환된 후에는 외부 호출 동안 유지할 DB 잠금이 남지 않는다")
    void releasesLockBeforeReturningClaim() throws Exception {
        Long id = pendingJob((short) 0);

        assertThat(buildingCoordinateRetryClaimService.claimNext()).isPresent();

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT status FROM Building_coordinate_retry_jobs WHERE building_id = ? FOR UPDATE NOWAIT
                    """)) {
                statement.setLong(1, id);
                try (ResultSet result = statement.executeQuery()) {
                    assertThat(result.next()).isTrue();

                    assertThat(result.getString(1)).isEqualTo("PROCESSING");
                }
            } finally {
                connection.rollback();
            }
        }
    }

    @Test
    @DisplayName("현재 소유자의 성공 결과는 좌표와 완료 상태를 함께 저장한다")
    void completesWithCoordinates() {
        Claim claim = processingJob((short) 2);

        assertThat(buildingCoordinateRetryClaimService.complete(claim, point())).isTrue();

        BuildingCoordinateRetryJob saved = job(claim.buildingId());
        assertThat(saved.getStatus()).isEqualTo(BuildingCoordinateRetryJobStatus.COMPLETED);

        assertThat(saved.getRetryFailureCount()).isEqualTo((short) 2);

        assertThat(saved.getClaimVersion()).isEqualTo(claim.version());

        assertThat(saved.getNextRetryAt()).isNull();

        assertThat(saved.getLeaseExpiresAt()).isNull();

        assertThat(locationCount(claim.buildingId())).isEqualTo(1);

        assertThat(jdbcTemplate.queryForObject(
            "SELECT ST_Longitude(location) FROM Building_locations WHERE building_id = ?",
            Double.class, claim.buildingId())).isEqualTo(127.0276);

        assertThat(jdbcTemplate.queryForObject(
            "SELECT ST_Latitude(location) FROM Building_locations WHERE building_id = ?",
            Double.class, claim.buildingId())).isEqualTo(37.4979);

        assertThat(buildingCoordinateRetryClaimService.complete(claim, point())).isFalse();
    }

    @Test
    @DisplayName("재대기 결과는 실패 횟수와 오류를 저장하고 기록 시각부터 한 시간 후로 예약한다")
    void savesRetryResult() {
        Claim claim = processingJob((short) 1);
        LocalDateTime before = databaseTime();

        assertThat(buildingCoordinateRetryClaimService.retry(claim, "KAKAO_SERVER_ERROR", (short) 2)).isTrue();

        BuildingCoordinateRetryJob saved = job(claim.buildingId());
        assertThat(saved.getStatus()).isEqualTo(BuildingCoordinateRetryJobStatus.PENDING);

        assertThat(saved.getRetryFailureCount()).isEqualTo((short) 2);

        assertThat(saved.getLastErrorType()).isEqualTo("KAKAO_SERVER_ERROR");

        assertThat(saved.getNextRetryAt()).isBetween(before.plusHours(1), databaseTime().plusHours(1));

        assertThat(saved.getLeaseExpiresAt()).isNull();

        assertThat(saved.getFailureReason()).isNull();

        assertThat(saved.getClaimVersion()).isEqualTo(claim.version());
    }

    @Test
    @DisplayName("최종 실패 결과는 사유와 횟수를 저장하고 다음 실행과 선점 시각을 비운다")
    void savesTerminalFailure() {
        Claim claim = processingJob((short) 2);

        assertThat(buildingCoordinateRetryClaimService.fail(claim, "NETWORK_ERROR", "RETRY_EXHAUSTED", (short) 3))
            .isTrue();

        BuildingCoordinateRetryJob saved = job(claim.buildingId());
        assertThat(saved.getStatus()).isEqualTo(BuildingCoordinateRetryJobStatus.FAILED);

        assertThat(saved.getRetryFailureCount()).isEqualTo((short) 3);

        assertThat(saved.getLastErrorType()).isEqualTo("NETWORK_ERROR");

        assertThat(saved.getFailureReason()).isEqualTo("RETRY_EXHAUSTED");

        assertThat(saved.getNextRetryAt()).isNull();

        assertThat(saved.getLeaseExpiresAt()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"complete", "retry", "fail"})
    @DisplayName("재선점 후 이전 버전의 성공과 실패 결과는 모두 폐기한다")
    void rejectsOldOwnerAfterReclaim(String operation) {
        Claim old = processingJob((short) 1);
        expire(old.buildingId());
        jdbcTemplate.update("""
            UPDATE Building_coordinate_retry_jobs SET claim_version = claim_version + 1,
                lease_expires_at = TIMESTAMPADD(MINUTE, 10, CURRENT_TIMESTAMP(6)) WHERE building_id = ?
            """, old.buildingId());

        assertThat(save(operation, old)).isFalse();

        assertProcessing(old.buildingId(), old.version() + 1, (short) 1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"complete", "retry", "fail"})
    @DisplayName("다른 워커의 재선점 전이라도 만료된 소유자의 결과는 저장하지 않는다")
    void rejectsExpiredOwner(String operation) {
        Claim claim = processingJob((short) 1);
        expire(claim.buildingId());

        assertThat(save(operation, claim)).isFalse();

        assertProcessing(claim.buildingId(), claim.version(), (short) 1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "COMPLETED", "FAILED"})
    @DisplayName("버전이 같아도 처리 중이 아닌 작업에는 좌표 결과를 저장하지 않는다")
    void rejectsNonProcessingJob(String status) {
        Claim claim = processingJob((short) 0);
        jdbcTemplate.update("""
            UPDATE Building_coordinate_retry_jobs SET status = ?, next_retry_at = ?,
                lease_expires_at = NULL, failure_reason = ? WHERE building_id = ?
            """, status, "PENDING".equals(status) ? databaseTime().plusHours(1) : null,
            "FAILED".equals(status) ? "UNKNOWN_ERROR" : null, claim.buildingId());

        assertThat(buildingCoordinateRetryClaimService.complete(claim, point())).isFalse();

        assertThat(job(claim.buildingId()).getStatus().name()).isEqualTo(status);

        assertThat(locationCount(claim.buildingId())).isZero();
    }

    @Test
    @DisplayName("좌표 INSERT 실패 시 완료 상태도 롤백하고 횟수를 보존한다")
    void rollsBackCompletionWhenLocationInsertFails() {
        Claim claim = processingJob((short) 2);
        jdbcTemplate.execute(
            "ALTER TABLE Building_locations ADD CONSTRAINT reject_retry_location CHECK (building_id <> "
            + claim.buildingId() + ")");
        try {
            assertThatThrownBy(() -> buildingCoordinateRetryClaimService.complete(claim, point()))
                .isInstanceOf(DataAccessException.class);

            assertProcessing(claim.buildingId(), claim.version(), (short) 2);
        } finally {
            jdbcTemplate.execute("ALTER TABLE Building_locations DROP CHECK reject_retry_location");
        }
    }

    @Test
    @DisplayName("작업 완료 UPDATE 실패 시 먼저 저장한 좌표도 함께 롤백한다")
    void rollsBackCoordinatesWhenJobUpdateFails() {
        Claim claim = processingJob((short) 2);
        jdbcTemplate.execute(
            "ALTER TABLE Building_coordinate_retry_jobs ADD CONSTRAINT reject_completed CHECK (building_id <> "
            + claim.buildingId() + " OR status <> 'COMPLETED')");
        try {
            assertThatThrownBy(() -> buildingCoordinateRetryClaimService.complete(claim, point()))
                .isInstanceOf(DataAccessException.class);

            assertProcessing(claim.buildingId(), claim.version(), (short) 2);
        } finally {
            jdbcTemplate.execute("ALTER TABLE Building_coordinate_retry_jobs DROP CHECK reject_completed");
        }
    }

    @Test
    @DisplayName("재대기 결과 UPDATE 실패 시 횟수와 기존 선점 상태를 보존한다")
    void rollsBackRetryFailureCountWhenUpdateFails() {
        Claim claim = processingJob((short) 1);
        jdbcTemplate.execute(
            "ALTER TABLE Building_coordinate_retry_jobs ADD CONSTRAINT reject_pending CHECK (building_id <> "
            + claim.buildingId() + " OR status <> 'PENDING')");
        try {
            assertThatThrownBy(() -> buildingCoordinateRetryClaimService.retry(claim, "KAKAO_SERVER_ERROR", (short) 2))
                .isInstanceOf(DataAccessException.class);

            assertProcessing(claim.buildingId(), claim.version(), (short) 1);
        } finally {
            jdbcTemplate.execute("ALTER TABLE Building_coordinate_retry_jobs DROP CHECK reject_pending");
        }
    }

    private Long pendingJob(short count) {
        Long id = transactionTemplate.execute(transaction -> {
            User manager = new User(UUID.randomUUID() + "@example.com", "password", "관리자", null);
            entityManager.persist(manager);
            Building building = new Building(manager, ADDRESS, "테스트빌");
            entityManager.persist(building);
            buildingCoordinateRetryJobRepository.saveAndFlush(
                new BuildingCoordinateRetryJob(building, "NETWORK_ERROR", databaseTime().minusHours(1)));
            return building.getId();
        });
        jdbcTemplate.update("UPDATE Building_coordinate_retry_jobs SET retry_failure_count = ? WHERE building_id = ?",
            count, id);
        return id;
    }

    private Claim processingJob(short count) {
        Long id = pendingJob(count);
        jdbcTemplate.update("""
            UPDATE Building_coordinate_retry_jobs SET status = 'PROCESSING', next_retry_at = NULL,
                claim_version = 1, lease_expires_at = TIMESTAMPADD(MINUTE, 10, CURRENT_TIMESTAMP(6))
            WHERE building_id = ?
            """, id);
        return new Claim(id, 1, ADDRESS, count);
    }

    private void expire(Long id) {
        jdbcTemplate.update("""
            UPDATE Building_coordinate_retry_jobs SET lease_expires_at = TIMESTAMPADD(SECOND, -1, CURRENT_TIMESTAMP(6))
            WHERE building_id = ?
            """, id);
    }

    private void lock(Connection connection, Long id) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT building_id FROM Building_coordinate_retry_jobs WHERE building_id = ? FOR UPDATE
                """)) {
            statement.setLong(1, id);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
            }
        }
    }

    private boolean save(String operation, Claim claim) {
        return switch (operation) {
            case "complete" -> buildingCoordinateRetryClaimService.complete(claim, point());
            case "retry" -> buildingCoordinateRetryClaimService.retry(claim, "KAKAO_SERVER_ERROR", (short) 2);
            case "fail" -> buildingCoordinateRetryClaimService.fail(
                claim, "NETWORK_ERROR", "RETRY_EXHAUSTED", (short) 3);
            default -> throw new IllegalArgumentException(operation);
        };
    }

    private void assertProcessing(Long id, long version, short count) {
        BuildingCoordinateRetryJob saved = job(id);
        assertThat(saved.getStatus()).isEqualTo(BuildingCoordinateRetryJobStatus.PROCESSING);

        assertThat(saved.getClaimVersion()).isEqualTo(version);

        assertThat(saved.getRetryFailureCount()).isEqualTo(count);

        assertThat(saved.getLastErrorType()).isEqualTo("NETWORK_ERROR");

        assertThat(saved.getFailureReason()).isNull();

        assertThat(saved.getNextRetryAt()).isNull();

        assertThat(locationCount(id)).isZero();
    }

    private BuildingCoordinateRetryJob job(Long id) {
        return buildingCoordinateRetryJobRepository.findById(id).orElseThrow();
    }

    private long locationCount(Long id) {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM Building_locations WHERE building_id = ?", Long.class, id);
    }

    private LocalDateTime databaseTime() {
        return jdbcTemplate.queryForObject("SELECT CURRENT_TIMESTAMP(6)", LocalDateTime.class);
    }

    private Point point() {
        return new GeometryFactory(new PrecisionModel(), 4326).createPoint(new Coordinate(127.0276, 37.4979));
    }
}
