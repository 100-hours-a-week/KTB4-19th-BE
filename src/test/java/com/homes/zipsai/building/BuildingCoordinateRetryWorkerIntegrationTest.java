package com.homes.zipsai.building;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

import javax.sql.DataSource;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.homes.zipsai.building.client.KakaoAddressClient;
import com.homes.zipsai.building.client.KakaoAddressErrorType;
import com.homes.zipsai.building.client.KakaoAddressResult;
import com.homes.zipsai.building.domain.Building;
import com.homes.zipsai.building.domain.BuildingCoordinateRetryJob;
import com.homes.zipsai.building.repository.BuildingCoordinateRetryJobRepository;
import com.homes.zipsai.building.service.BuildingCoordinateRetryWorker;
import com.homes.zipsai.building.service.BuildingCoordinateRetryWorker.Outcome;
import com.homes.zipsai.user.domain.User;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

@SpringBootTest
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:tc:mysql:8.4.11:///zipsai_coordinate_retry_worker?TC_DAEMON=true",
    "spring.datasource.username=test",
    "spring.datasource.password=test",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.jpa.hibernate.naming.physical-strategy=org.hibernate.boot.model.naming.PhysicalNamingStrategyStandardImpl",
    "spring.jpa.defer-datasource-initialization=false",
    "spring.flyway.enabled=true"
})
class BuildingCoordinateRetryWorkerIntegrationTest {

    private static final String ADDRESS = "서울 강남구 테헤란로 123";
    private final Logger logger = (Logger) LoggerFactory.getLogger("structured-events");
    private final ListAppender<ILoggingEvent> listAppender = new ListAppender<>();

    @Autowired
    BuildingCoordinateRetryWorker buildingCoordinateRetryWorker;

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

    @MockitoBean
    KakaoAddressClient kakaoAddressClient;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM Building_coordinate_retry_jobs");
        jdbcTemplate.update("DELETE FROM Building_locations");
        listAppender.list.clear();
        listAppender.start();
        logger.addAppender(listAppender);
        when(kakaoAddressClient.findCoordinates(ADDRESS)).thenReturn(new KakaoAddressResult.Success(point()));
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(listAppender);
        listAppender.stop();
    }

    @Test
    @DisplayName("실행할 작업이 없으면 외부 API를 호출하지 않는다")
    void returnsNoWorkWithoutCallingKakao() {
        assertThat(buildingCoordinateRetryWorker.executeNext()).isEqualTo(Outcome.NO_WORK);

        verifyNoInteractions(kakaoAddressClient);
    }

    @Test
    @DisplayName("재시도 가능 시각 전의 작업은 실행하지 않는다")
    void waitsUntilNextRetry() {
        Long id = pendingJob((short) 1);
        jdbcTemplate.update("""
            UPDATE Building_coordinate_retry_jobs SET next_retry_at = TIMESTAMPADD(HOUR, 1, CURRENT_TIMESTAMP(6))
            WHERE building_id = ?
            """, id);

        assertThat(buildingCoordinateRetryWorker.executeNext()).isEqualTo(Outcome.NO_WORK);

        assertState(id, "PENDING", (short) 1, null);

        verifyNoInteractions(kakaoAddressClient);
    }

    @Test
    @DisplayName("조회는 트랜잭션 밖에서 실행하고 성공한 좌표와 작업 완료를 함께 저장한다")
    void completesOutsideHttpTransaction() {
        Long id = pendingJob((short) 2);
        when(kakaoAddressClient.findCoordinates(ADDRESS)).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new KakaoAddressResult.Success(point());
        });

        assertThat(buildingCoordinateRetryWorker.executeNext()).isEqualTo(Outcome.CONTINUE);

        assertState(id, "COMPLETED", (short) 2, null);

        assertThat(locationCount(id)).isEqualTo(1);

        assertThat(jdbcTemplate.queryForObject(
            "SELECT ST_Longitude(location) FROM Building_locations WHERE building_id = ?", Double.class, id))
            .isEqualTo(127.0276);

        assertThat(jdbcTemplate.queryForObject(
            "SELECT ST_Latitude(location) FROM Building_locations WHERE building_id = ?", Double.class, id))
            .isEqualTo(37.4979);
    }

    @ParameterizedTest
    @MethodSource("limitedFailures")
    @DisplayName("서버와 네트워크 재시도 실패는 저장된 세 번째 실패에서만 종료한다")
    void limitsRetryFailures(KakaoAddressErrorType errorType, short count) {
        Long id = pendingJob(count);
        when(kakaoAddressClient.findCoordinates(ADDRESS)).thenReturn(new KakaoAddressResult.Failure(errorType));
        LocalDateTime before = databaseTime();

        assertThat(buildingCoordinateRetryWorker.executeNext()).isEqualTo(Outcome.STOP_ROUND);

        short expectedCount = (short) (count + 1);
        assertState(id, expectedCount == 3 ? "FAILED" : "PENDING", expectedCount,
            expectedCount == 3 ? "RETRY_EXHAUSTED" : null);

        assertThat(job(id).getLastErrorType()).isEqualTo(errorType.name());

        assertThat(locationCount(id)).isZero();

        if (expectedCount < 3) {
            assertThat(job(id).getNextRetryAt()).isBetween(before.plusHours(1), databaseTime().plusHours(1));
        }
    }

    @ParameterizedTest
    @MethodSource("unlimitedFailures")
    @DisplayName("설정과 할당량 및 응답 형식 오류는 기존 횟수를 보존하고 한 시간 후 재시도하며 원인을 기록한다")
    void preservesCountForUnlimitedErrors(KakaoAddressErrorType errorType, short count) {
        Long id = pendingJob(count);
        when(kakaoAddressClient.findCoordinates(ADDRESS)).thenReturn(new KakaoAddressResult.Failure(errorType));
        LocalDateTime before = databaseTime();

        assertThat(buildingCoordinateRetryWorker.executeNext()).isEqualTo(Outcome.STOP_ROUND);

        assertState(id, "PENDING", count, null);

        assertThat(job(id).getLastErrorType()).isEqualTo(errorType.name());

        assertThat(job(id).getNextRetryAt()).isBetween(before.plusHours(1), databaseTime().plusHours(1));

        assertThat(listAppender.list).anySatisfy(event -> assertThat(event.getFormattedMessage())
            .contains(errorType.name()).contains(id.toString()));
    }

    @Test
    @DisplayName("무제한 오류를 거쳐도 누적된 제한 실패 횟수를 초기화하지 않는다")
    void preservesLimitedFailuresAcrossUnlimitedError() {
        Long id = pendingJob((short) 1);
        when(kakaoAddressClient.findCoordinates(ADDRESS)).thenReturn(
            new KakaoAddressResult.Failure(KakaoAddressErrorType.QUOTA_EXCEEDED),
            new KakaoAddressResult.Failure(KakaoAddressErrorType.NETWORK_ERROR),
            new KakaoAddressResult.Failure(KakaoAddressErrorType.KAKAO_SERVER_ERROR));

        buildingCoordinateRetryWorker.executeNext();
        assertState(id, "PENDING", (short) 1, null);

        makeDue(id);
        buildingCoordinateRetryWorker.executeNext();
        assertState(id, "PENDING", (short) 2, null);

        makeDue(id);
        buildingCoordinateRetryWorker.executeNext();
        assertState(id, "FAILED", (short) 3, "RETRY_EXHAUSTED");

        assertThat(job(id).getLastErrorType()).isEqualTo("KAKAO_SERVER_ERROR");
    }

    @ParameterizedTest
    @EnumSource(value = KakaoAddressErrorType.class, names = {
        "ADDRESS_NOT_FOUND", "AMBIGUOUS_ADDRESS", "NON_BUILDING_ADDRESS", "UNKNOWN_HTTP_ERROR", "UNKNOWN_ERROR"
    })
    @DisplayName("주소 오류와 미분류 오류는 건물을 유지하고 작업만 종료하며 HTTP 오류만 회차를 중단한다")
    void terminatesNonRetryableErrors(KakaoAddressErrorType errorType) {
        Long id = pendingJob((short) 2);
        when(kakaoAddressClient.findCoordinates(ADDRESS)).thenReturn(new KakaoAddressResult.Failure(errorType));

        assertThat(buildingCoordinateRetryWorker.executeNext()).isEqualTo(
            errorType == KakaoAddressErrorType.UNKNOWN_HTTP_ERROR ? Outcome.STOP_ROUND : Outcome.CONTINUE);

        assertState(id, "FAILED", (short) 2, errorType.name());

        assertThat(job(id).getLastErrorType()).isEqualTo(errorType.name());

        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM Buildings WHERE building_id = ?", Long.class, id)).isEqualTo(1);

        assertThat(locationCount(id)).isZero();
    }

    @Test
    @DisplayName("호출 전에 삭제된 건물은 API를 호출하지 않고 삭제 사유로 작업을 종료한다")
    void cancelsDeletedBuildingBeforeCallingKakao() {
        Long id = pendingJob((short) 2);
        deleteBuilding(id);

        assertThat(buildingCoordinateRetryWorker.executeNext()).isEqualTo(Outcome.CONTINUE);

        assertState(id, "FAILED", (short) 2, "BUILDING_DELETED");

        assertThat(locationCount(id)).isZero();

        verifyNoInteractions(kakaoAddressClient);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUCCESS", "NETWORK_ERROR", "QUOTA_EXCEEDED", "ADDRESS_NOT_FOUND"})
    @DisplayName("조회 중 건물이 삭제되면 조회 결과 대신 삭제 사유를 보관하고 횟수와 좌표를 변경하지 않는다")
    void cancelsBuildingDeletedDuringLookup(String result) {
        Long id = pendingJob((short) 2);
        when(kakaoAddressClient.findCoordinates(ADDRESS)).thenAnswer(invocation -> {
            deleteBuilding(id);
            return response(result);
        });

        buildingCoordinateRetryWorker.executeNext();

        assertState(id, "FAILED", (short) 2, "BUILDING_DELETED");

        assertThat(locationCount(id)).isZero();

        verify(kakaoAddressClient).findCoordinates(ADDRESS);
    }

    @Test
    @DisplayName("조회 직후 삭제 트랜잭션과 경합하면 삭제 커밋을 확인한 뒤 좌표 저장을 취소한다")
    void serializesResultWithConcurrentBuildingDeletion() throws Exception {
        Long id = pendingJob((short) 1);
        CountDownLatch lookupEntered = new CountDownLatch(1);
        CountDownLatch releaseLookup = new CountDownLatch(1);
        when(kakaoAddressClient.findCoordinates(ADDRESS)).thenAnswer(invocation -> {
            lookupEntered.countDown();
            if (!releaseLookup.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("lookup release timed out");
            }
            return new KakaoAddressResult.Success(point());
        });
        try (Connection connection = dataSource.getConnection();
                ExecutorService executorService = Executors.newSingleThreadExecutor()) {
            connection.setAutoCommit(false);
            Future<Outcome> execution = executorService.submit(buildingCoordinateRetryWorker::executeNext);
            try {
                assertThat(lookupEntered.await(10, TimeUnit.SECONDS)).isTrue();

                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE Buildings SET deleted_at = CURRENT_TIMESTAMP(6) WHERE building_id = ?")) {
                    statement.setLong(1, id);
                    statement.executeUpdate();
                }
                releaseLookup.countDown();

                assertThatThrownBy(() -> execution.get(300, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);

                connection.commit();

                assertThat(execution.get(10, TimeUnit.SECONDS)).isEqualTo(Outcome.CONTINUE);

                assertState(id, "FAILED", (short) 1, "BUILDING_DELETED");

                assertThat(locationCount(id)).isZero();
            } finally {
                connection.rollback();
                releaseLookup.countDown();
            }
        }
    }

    @ParameterizedTest
    @MethodSource("storageFailures")
    @DisplayName("결과 저장 실패는 횟수와 좌표를 롤백하고 선점 만료 후 같은 작업을 다시 처리한다")
    void recoversUnrecordedResult(String result, short count, String status, short expectedCount, String reason) {
        Long id = pendingJob(count);
        when(kakaoAddressClient.findCoordinates(ADDRESS)).thenReturn(response(result));
        jdbcTemplate.execute("ALTER TABLE Building_coordinate_retry_jobs ADD CONSTRAINT reject_worker_result "
            + "CHECK (building_id <> " + id + " OR claim_version = 0 OR status <> '" + status + "')");
        try {
            assertThat(buildingCoordinateRetryWorker.executeNext()).isEqualTo(Outcome.STOP_ROUND);

            assertState(id, "PROCESSING", count, null);

            assertThat(job(id).getLastErrorType()).isEqualTo("NETWORK_ERROR");

            assertThat(job(id).getClaimVersion()).isEqualTo(1);

            assertThat(locationCount(id)).isZero();
        } finally {
            jdbcTemplate.execute("ALTER TABLE Building_coordinate_retry_jobs DROP CHECK reject_worker_result");
        }
        expire(id);
        buildingCoordinateRetryWorker.executeNext();

        assertState(id, status, expectedCount, reason);

        assertThat(job(id).getClaimVersion()).isEqualTo(2);

        assertThat(locationCount(id)).isEqualTo("SUCCESS".equals(result) ? 1 : 0);
    }

    @Test
    @DisplayName("작업 중단으로 결과를 기록하지 못하면 횟수를 보존하고 다음 워커가 만료된 선점을 회수한다")
    void recoversAfterWorkerAborts() {
        Long id = pendingJob((short) 1);
        when(kakaoAddressClient.findCoordinates(ADDRESS)).thenThrow(new AssertionError("worker aborted"));

        assertThatThrownBy(buildingCoordinateRetryWorker::executeNext).isInstanceOf(AssertionError.class);

        assertState(id, "PROCESSING", (short) 1, null);

        doReturn(new KakaoAddressResult.Success(point())).when(kakaoAddressClient).findCoordinates(ADDRESS);
        expire(id);
        buildingCoordinateRetryWorker.executeNext();

        assertState(id, "COMPLETED", (short) 1, null);

        assertThat(job(id).getClaimVersion()).isEqualTo(2);

        assertThat(locationCount(id)).isEqualTo(1);
    }

    @Test
    @DisplayName("이미 중단된 워커는 작업을 선점하거나 API를 호출하지 않는다")
    void skipsWorkWhenAlreadyInterrupted() {
        Long id = pendingJob((short) 1);
        try {
            Thread.currentThread().interrupt();

            assertThat(buildingCoordinateRetryWorker.executeNext()).isEqualTo(Outcome.STOP_ROUND);
        } finally {
            Thread.interrupted();
        }
        assertState(id, "PENDING", (short) 1, null);

        verifyNoInteractions(kakaoAddressClient);
    }

    @Test
    @DisplayName("호출 중 워커가 중단되면 API 실패 결과를 횟수에 반영하지 않고 선점 만료로 복구한다")
    void ignoresFailureAfterWorkerIsInterrupted() {
        Long id = pendingJob((short) 1);
        when(kakaoAddressClient.findCoordinates(ADDRESS)).thenAnswer(invocation -> {
            Thread.currentThread().interrupt();
            return new KakaoAddressResult.Failure(KakaoAddressErrorType.NETWORK_ERROR);
        });
        try {
            assertThat(buildingCoordinateRetryWorker.executeNext()).isEqualTo(Outcome.STOP_ROUND);
        } finally {
            Thread.interrupted();
        }
        assertState(id, "PROCESSING", (short) 1, null);

        doReturn(new KakaoAddressResult.Success(point())).when(kakaoAddressClient).findCoordinates(ADDRESS);
        expire(id);
        buildingCoordinateRetryWorker.executeNext();

        assertState(id, "COMPLETED", (short) 1, null);

        assertThat(job(id).getClaimVersion()).isEqualTo(2);
    }

    private static Stream<Arguments> limitedFailures() {
        return Stream.of(KakaoAddressErrorType.KAKAO_SERVER_ERROR, KakaoAddressErrorType.NETWORK_ERROR)
            .flatMap(errorType -> Stream.of((short) 0, (short) 1, (short) 2)
                .map(count -> Arguments.of(errorType, count)));
    }

    private static Stream<Arguments> unlimitedFailures() {
        return Stream.of(KakaoAddressErrorType.AUTHENTICATION_ERROR, KakaoAddressErrorType.PERMISSION_ERROR,
                KakaoAddressErrorType.APP_STATE_ERROR, KakaoAddressErrorType.QUOTA_EXCEEDED,
                KakaoAddressErrorType.REQUEST_CONFIGURATION_ERROR, KakaoAddressErrorType.INVALID_RESPONSE)
            .flatMap(errorType -> Stream.of((short) 0, (short) 2, (short) 3)
                .map(count -> Arguments.of(errorType, count)));
    }

    private static Stream<Arguments> storageFailures() {
        return Stream.of(
            Arguments.of("SUCCESS", (short) 1, "COMPLETED", (short) 1, null),
            Arguments.of("NETWORK_ERROR", (short) 1, "PENDING", (short) 2, null),
            Arguments.of("KAKAO_SERVER_ERROR", (short) 2, "FAILED", (short) 3, "RETRY_EXHAUSTED"));
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

    private KakaoAddressResult response(String result) {
        return "SUCCESS".equals(result) ? new KakaoAddressResult.Success(point())
            : new KakaoAddressResult.Failure(KakaoAddressErrorType.valueOf(result));
    }

    private void makeDue(Long id) {
        jdbcTemplate.update("""
            UPDATE Building_coordinate_retry_jobs SET next_retry_at = TIMESTAMPADD(SECOND, -1, CURRENT_TIMESTAMP(6))
            WHERE building_id = ?
            """, id);
    }

    private void expire(Long id) {
        jdbcTemplate.update("""
            UPDATE Building_coordinate_retry_jobs SET lease_expires_at = TIMESTAMPADD(SECOND, -1, CURRENT_TIMESTAMP(6))
            WHERE building_id = ?
            """, id);
    }

    private void deleteBuilding(Long id) {
        jdbcTemplate.update("UPDATE Buildings SET deleted_at = CURRENT_TIMESTAMP(6) WHERE building_id = ?", id);
    }

    private void assertState(Long id, String status, short count, String reason) {
        BuildingCoordinateRetryJob saved = job(id);
        assertThat(saved.getStatus().name()).isEqualTo(status);

        assertThat(saved.getRetryFailureCount()).isEqualTo(count);

        assertThat(saved.getFailureReason()).isEqualTo(reason);

        if ("PENDING".equals(status)) {
            assertThat(saved.getNextRetryAt()).isNotNull();

            assertThat(saved.getLeaseExpiresAt()).isNull();
        } else if ("PROCESSING".equals(status)) {
            assertThat(saved.getNextRetryAt()).isNull();

            assertThat(saved.getLeaseExpiresAt()).isNotNull();
        } else {
            assertThat(saved.getNextRetryAt()).isNull();

            assertThat(saved.getLeaseExpiresAt()).isNull();
        }
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
