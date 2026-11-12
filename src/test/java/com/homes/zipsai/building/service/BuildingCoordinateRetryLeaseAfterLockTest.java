package com.homes.zipsai.building.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;

import com.homes.zipsai.building.domain.Building;
import com.homes.zipsai.building.domain.BuildingCoordinateRetryJob;
import com.homes.zipsai.building.domain.BuildingCoordinateRetryJobStatus;
import com.homes.zipsai.building.repository.BuildingCoordinateRetryJobRepository;
import com.homes.zipsai.building.repository.BuildingLocationRepository;
import com.homes.zipsai.building.service.BuildingCoordinateRetryClaimService.Claim;

class BuildingCoordinateRetryLeaseAfterLockTest {

    @Test
    @DisplayName("건물 잠금을 기다리는 동안 선점이 만료되면 좌표와 작업 결과를 저장하지 않는다")
    void rejectsResultWhenLeaseExpiresWhileWaitingForBuildingLock() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 16, 12, 0);
        BuildingCoordinateRetryJob job = new BuildingCoordinateRetryJob(
            new Building(null, "서울 테스트로 1", "테스트빌"), "NETWORK_ERROR", now);
        job.claim(now);
        BuildingCoordinateRetryJobRepository buildingCoordinateRetryJobRepository =
            mock(BuildingCoordinateRetryJobRepository.class);
        BuildingLocationRepository buildingLocationRepository = mock(BuildingLocationRepository.class);
        BuildingCoordinateRetryClaimService buildingCoordinateRetryClaimService =
            new BuildingCoordinateRetryClaimService(buildingCoordinateRetryJobRepository, buildingLocationRepository);
        AtomicBoolean buildingLockAcquired = new AtomicBoolean();
        when(buildingCoordinateRetryJobRepository.findLockedById(1L)).thenReturn(Optional.of(job));
        when(buildingCoordinateRetryJobRepository.currentTime()).thenAnswer(invocation ->
            buildingLockAcquired.get() ? now.plusMinutes(10) : now);
        when(buildingCoordinateRetryJobRepository.findBuildingDeletedAtForUpdate(1L)).thenAnswer(invocation -> {
            buildingLockAcquired.set(true);
            return null;
        });
        Claim claim = new Claim(1L, 1, "서울 테스트로 1", (short) 0);
        Point point = new GeometryFactory(new PrecisionModel(), 4326).createPoint(new Coordinate(127, 37));

        assertThat(buildingCoordinateRetryClaimService.complete(claim, point)).isFalse();

        assertThat(job.getStatus()).isEqualTo(BuildingCoordinateRetryJobStatus.PROCESSING);

        assertThat(job.getRetryFailureCount()).isZero();

        verifyNoInteractions(buildingLocationRepository);
    }
}
