package com.homes.zipsai.building.service;

import java.time.LocalDateTime;
import java.util.Optional;

import org.locationtech.jts.geom.Point;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.homes.zipsai.building.domain.BuildingCoordinateRetryJob;
import com.homes.zipsai.building.domain.BuildingLocation;
import com.homes.zipsai.building.repository.BuildingCoordinateRetryJobRepository;
import com.homes.zipsai.building.repository.BuildingLocationRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.REQUIRES_NEW)
public class BuildingCoordinateRetryClaimService {

    private final BuildingCoordinateRetryJobRepository buildingCoordinateRetryJobRepository;
    private final BuildingLocationRepository buildingLocationRepository;

    public Optional<Claim> claimNext() {
        return buildingCoordinateRetryJobRepository
            .findNextForUpdate(buildingCoordinateRetryJobRepository.currentTime())
            .map(job -> {
                job.claim(buildingCoordinateRetryJobRepository.currentTime());
                return new Claim(job.getId(), job.getClaimVersion(), job.getBuilding().getRoadAddress(),
                    job.getRetryFailureCount());
            });
    }

    public boolean prepare(Claim claim) {
        return activeJob(claim).isPresent();
    }

    public boolean complete(Claim claim, Point coordinates) {
        return activeJob(claim).map(job -> {
            buildingLocationRepository.save(new BuildingLocation(job.getBuilding(), coordinates));
            job.complete();
            return true;
        }).orElse(false);
    }

    public boolean retry(Claim claim, String lastErrorType, short retryFailureCount) {
        return activeJob(claim).map(job -> {
            job.retry(lastErrorType, retryFailureCount,
                buildingCoordinateRetryJobRepository.currentTime().plusHours(1));
            return true;
        }).orElse(false);
    }

    public boolean fail(Claim claim, String lastErrorType, String failureReason, short retryFailureCount) {
        return activeJob(claim).map(job -> {
            job.fail(lastErrorType, failureReason, retryFailureCount);
            return true;
        }).orElse(false);
    }

    private Optional<BuildingCoordinateRetryJob> activeJob(Claim claim) {
        return buildingCoordinateRetryJobRepository.findLockedById(claim.buildingId())
            .filter(job -> {
                LocalDateTime deletedAt = buildingCoordinateRetryJobRepository
                    .findBuildingDeletedAtForUpdate(claim.buildingId());
                if (!job.isOwnedBy(claim.version(), buildingCoordinateRetryJobRepository.currentTime())) {
                    return false;
                }
                if (deletedAt != null) {
                    job.fail("BUILDING_DELETED", "BUILDING_DELETED", job.getRetryFailureCount());
                    return false;
                }
                return true;
            });
    }

    public record Claim(Long buildingId, long version, String roadAddress, short retryFailureCount) {
    }
}
