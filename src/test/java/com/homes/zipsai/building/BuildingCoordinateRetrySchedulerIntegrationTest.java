package com.homes.zipsai.building;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import com.homes.zipsai.building.client.KakaoAddressClient;
import com.homes.zipsai.building.client.KakaoAddressErrorType;
import com.homes.zipsai.building.client.KakaoAddressResult;
import com.homes.zipsai.building.domain.Building;
import com.homes.zipsai.building.domain.BuildingCoordinateRetryJob;
import com.homes.zipsai.building.domain.BuildingCoordinateRetryJobStatus;
import com.homes.zipsai.building.repository.BuildingCoordinateRetryJobRepository;
import com.homes.zipsai.building.scheduler.BuildingCoordinateRetryScheduler;
import com.homes.zipsai.building.service.BuildingCoordinateRetryClaimService;
import com.homes.zipsai.building.service.BuildingCoordinateRetryWorker;
import com.homes.zipsai.global.logging.StructuredLogger;
import com.homes.zipsai.user.domain.User;

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
class BuildingCoordinateRetrySchedulerIntegrationTest {

    @Autowired
    BuildingCoordinateRetryScheduler buildingCoordinateRetryScheduler;

    @Autowired
    BuildingCoordinateRetryClaimService buildingCoordinateRetryClaimService;

    @Autowired
    BuildingCoordinateRetryJobRepository buildingCoordinateRetryJobRepository;

    @Autowired
    StructuredLogger structuredLogger;

    @Autowired
    EntityManager entityManager;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @MockitoBean
    KakaoAddressClient kakaoAddressClient;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM Building_coordinate_retry_jobs");
        jdbcTemplate.update("DELETE FROM Building_locations");
    }

    @Test
    @DisplayName("주소 오류 뒤에도 같은 회차에서 나머지 대기 작업을 처리하고 재시도 시각 전 작업은 남긴다")
    void drainsDueJobsPastAddressFailure() {
        Long invalidId = pendingJob("서울 테스트로 1");
        Long firstSuccessId = pendingJob("서울 테스트로 2");
        Long secondSuccessId = pendingJob("서울 테스트로 3");
        Long futureId = pendingJob("서울 테스트로 4");
        jdbcTemplate.update("""
            UPDATE Building_coordinate_retry_jobs SET next_retry_at = TIMESTAMPADD(HOUR, 1, CURRENT_TIMESTAMP(6))
            WHERE building_id = ?
            """, futureId);
        when(kakaoAddressClient.findCoordinates("서울 테스트로 1"))
            .thenReturn(new KakaoAddressResult.Failure(KakaoAddressErrorType.ADDRESS_NOT_FOUND));
        when(kakaoAddressClient.findCoordinates("서울 테스트로 2")).thenReturn(new KakaoAddressResult.Success(point()));
        when(kakaoAddressClient.findCoordinates("서울 테스트로 3")).thenReturn(new KakaoAddressResult.Success(point()));

        buildingCoordinateRetryScheduler.retryCoordinates();

        assertThat(job(invalidId).getFailureReason()).isEqualTo("ADDRESS_NOT_FOUND");

        assertThat(job(invalidId).getStatus()).isEqualTo(BuildingCoordinateRetryJobStatus.FAILED);

        assertThat(job(firstSuccessId).getStatus()).isEqualTo(BuildingCoordinateRetryJobStatus.COMPLETED);

        assertThat(job(secondSuccessId).getStatus()).isEqualTo(BuildingCoordinateRetryJobStatus.COMPLETED);

        assertThat(job(futureId).getClaimVersion()).isZero();

        assertThat(locationCount()).isEqualTo(2);

        verify(kakaoAddressClient, times(3)).findCoordinates(anyString());
    }

    @Test
    @DisplayName("두 인스턴스가 동시에 회차를 실행해도 서로 다른 작업을 선점하고 각 좌표를 한 번만 저장한다")
    void processesDistinctClaimsAcrossInstances() throws Exception {
        Long firstId = pendingJob("서울 동시로 1");
        Long secondId = pendingJob("서울 동시로 2");
        CountDownLatch bothCalling = new CountDownLatch(2);
        ConcurrentHashMap<String, AtomicInteger> calls = new ConcurrentHashMap<>();
        when(kakaoAddressClient.findCoordinates(anyString())).thenAnswer(invocation -> {
            String address = invocation.getArgument(0);
            calls.computeIfAbsent(address, key -> new AtomicInteger()).incrementAndGet();
            bothCalling.countDown();
            if (!bothCalling.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("두 인스턴스 호출 시간 초과");
            }
            return new KakaoAddressResult.Success(point());
        });
        BuildingCoordinateRetryScheduler otherBuildingCoordinateRetryScheduler = newInstance();
        try (ExecutorService executorService = Executors.newFixedThreadPool(2)) {
            Future<?> first = executorService.submit(buildingCoordinateRetryScheduler::retryCoordinates);
            Future<?> second = executorService.submit(otherBuildingCoordinateRetryScheduler::retryCoordinates);
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        }

        assertThat(calls.keySet()).containsExactlyInAnyOrder("서울 동시로 1", "서울 동시로 2");

        assertThat(calls.values()).allSatisfy(count -> assertThat(count.get()).isEqualTo(1));

        assertThat(List.of(job(firstId), job(secondId))).allSatisfy(job -> {
            assertThat(job.getStatus()).isEqualTo(BuildingCoordinateRetryJobStatus.COMPLETED);

            assertThat(job.getClaimVersion()).isEqualTo(1);
        });

        assertThat(locationCount()).isEqualTo(2);
    }

    @ParameterizedTest
    @EnumSource(value = KakaoAddressErrorType.class, names = {
        "NETWORK_ERROR", "KAKAO_SERVER_ERROR", "AUTHENTICATION_ERROR", "INVALID_RESPONSE", "UNKNOWN_HTTP_ERROR"
    })
    @DisplayName("HTTP 통신 형식 오류는 해당 인스턴스 회차만 중단하고 다른 인스턴스와 다음 회차는 진행한다")
    void stopsOnlyFailingInstanceAndAllowsNextRound(KakaoAddressErrorType errorType) {
        Long failedId = pendingJob("서울 장애로 1");
        Long remainingId = pendingJob("서울 정상로 2");
        when(kakaoAddressClient.findCoordinates("서울 장애로 1"))
            .thenReturn(new KakaoAddressResult.Failure(errorType));
        when(kakaoAddressClient.findCoordinates("서울 정상로 2")).thenReturn(new KakaoAddressResult.Success(point()));

        buildingCoordinateRetryScheduler.retryCoordinates();

        assertThat(job(failedId).getLastErrorType()).isEqualTo(errorType.name());

        assertThat(job(remainingId).getClaimVersion()).isZero();

        newInstance().retryCoordinates();

        assertThat(job(remainingId).getStatus()).isEqualTo(BuildingCoordinateRetryJobStatus.COMPLETED);

        Long nextRoundId = pendingJob("서울 다음로 3");
        when(kakaoAddressClient.findCoordinates("서울 다음로 3")).thenReturn(new KakaoAddressResult.Success(point()));
        buildingCoordinateRetryScheduler.retryCoordinates();

        assertThat(job(nextRoundId).getStatus()).isEqualTo(BuildingCoordinateRetryJobStatus.COMPLETED);

        assertThat(locationCount()).isEqualTo(2);

        verify(kakaoAddressClient).findCoordinates("서울 장애로 1");
    }

    @Test
    @DisplayName("서버가 중단돼 기록하지 못한 작업은 새 인스턴스가 선점 만료 뒤 횟수 증가 없이 복구한다")
    void recoversAbortedRoundOnNewInstance() {
        Long id = pendingJob("서울 복구로 1");
        jdbcTemplate.update("UPDATE Building_coordinate_retry_jobs SET retry_failure_count = 1 WHERE building_id = ?",
            id);
        when(kakaoAddressClient.findCoordinates("서울 복구로 1")).thenThrow(new AssertionError("서버 중단"));

        assertThatThrownBy(buildingCoordinateRetryScheduler::retryCoordinates).isInstanceOf(AssertionError.class);

        assertThat(job(id).getStatus()).isEqualTo(BuildingCoordinateRetryJobStatus.PROCESSING);

        doReturn(new KakaoAddressResult.Success(point())).when(kakaoAddressClient).findCoordinates("서울 복구로 1");
        BuildingCoordinateRetryScheduler otherBuildingCoordinateRetryScheduler = newInstance();
        otherBuildingCoordinateRetryScheduler.retryCoordinates();

        verify(kakaoAddressClient).findCoordinates("서울 복구로 1");

        jdbcTemplate.update("""
            UPDATE Building_coordinate_retry_jobs SET lease_expires_at = TIMESTAMPADD(SECOND, -1, CURRENT_TIMESTAMP(6))
            WHERE building_id = ?
            """, id);
        otherBuildingCoordinateRetryScheduler.retryCoordinates();

        assertThat(job(id).getStatus()).isEqualTo(BuildingCoordinateRetryJobStatus.COMPLETED);

        assertThat(job(id).getRetryFailureCount()).isEqualTo((short) 1);

        assertThat(job(id).getClaimVersion()).isEqualTo(2);

        assertThat(locationCount()).isEqualTo(1);
    }

    private BuildingCoordinateRetryScheduler newInstance() {
        return new BuildingCoordinateRetryScheduler(new BuildingCoordinateRetryWorker(
            buildingCoordinateRetryClaimService, kakaoAddressClient, structuredLogger));
    }

    private Long pendingJob(String roadAddress) {
        return transactionTemplate.execute(transaction -> {
            User manager = new User(UUID.randomUUID() + "@example.com", "password", "관리자", null);
            entityManager.persist(manager);
            Building building = new Building(manager, roadAddress, "테스트빌");
            entityManager.persist(building);
            LocalDateTime now = jdbcTemplate.queryForObject("SELECT CURRENT_TIMESTAMP(6)", LocalDateTime.class);
            buildingCoordinateRetryJobRepository.saveAndFlush(
                new BuildingCoordinateRetryJob(building, "NETWORK_ERROR", now.minusHours(1)));
            return building.getId();
        });
    }

    private BuildingCoordinateRetryJob job(Long id) {
        return buildingCoordinateRetryJobRepository.findById(id).orElseThrow();
    }

    private long locationCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM Building_locations", Long.class);
    }

    private Point point() {
        return new GeometryFactory(new PrecisionModel(), 4326).createPoint(new Coordinate(127.0276, 37.4979));
    }
}
