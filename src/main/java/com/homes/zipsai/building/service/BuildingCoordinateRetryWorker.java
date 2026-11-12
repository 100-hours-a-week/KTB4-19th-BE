package com.homes.zipsai.building.service;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import com.homes.zipsai.building.client.KakaoAddressClient;
import com.homes.zipsai.building.client.KakaoAddressErrorType;
import com.homes.zipsai.building.client.KakaoAddressResult;
import com.homes.zipsai.building.service.BuildingCoordinateRetryClaimService.Claim;
import com.homes.zipsai.global.logging.StructuredLogger;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class BuildingCoordinateRetryWorker {

    private final BuildingCoordinateRetryClaimService buildingCoordinateRetryClaimService;
    private final KakaoAddressClient kakaoAddressClient;
    private final StructuredLogger structuredLogger;

    public Outcome executeNext() {
        if (Thread.currentThread().isInterrupted()) {
            return Outcome.STOP_ROUND;
        }
        Map<String, String> previousMdc = MDC.getCopyOfContextMap();
        Claim claim = null;
        MDC.clear();
        try {
            MDC.put("traceId", UUID.randomUUID().toString());
            Optional<Claim> next = buildingCoordinateRetryClaimService.claimNext();
            if (next.isEmpty()) {
                return Outcome.NO_WORK;
            }
            claim = next.orElseThrow();
            return execute(claim);
        } catch (RuntimeException exception) {
            structuredLogger.coordinateRetryFailed(claim == null ? null : claim.buildingId(),
                claim == null ? null : claim.version(), exception);
            return Outcome.STOP_ROUND;
        } finally {
            if (previousMdc == null) {
                MDC.clear();
            } else {
                MDC.setContextMap(previousMdc);
            }
        }
    }

    private Outcome execute(Claim claim) {
        if (!buildingCoordinateRetryClaimService.prepare(claim)) {
            return Outcome.CONTINUE;
        }
        KakaoAddressResult result = kakaoAddressClient.findCoordinates(claim.roadAddress());
        if (Thread.currentThread().isInterrupted()) {
            return Outcome.STOP_ROUND;
        }
        if (result instanceof KakaoAddressResult.Success success) {
            if (buildingCoordinateRetryClaimService.complete(claim, success.coordinates())) {
                structuredLogger.coordinateRetryResult(claim.buildingId(), claim.version(), "COMPLETED",
                    claim.retryFailureCount(), null, null);
            }
            return Outcome.CONTINUE;
        }
        KakaoAddressErrorType errorType = ((KakaoAddressResult.Failure) result).errorType();
        short count = claim.retryFailureCount();
        String status = "PENDING";
        String failureReason = null;
        boolean saved = switch (errorType.getRetryPolicy()) {
            case LIMITED -> {
                count = (short) Math.min(3, count + 1);
                if (count == 3) {
                    status = "FAILED";
                    failureReason = "RETRY_EXHAUSTED";
                    yield buildingCoordinateRetryClaimService.fail(claim, errorType.name(), failureReason, count);
                }
                yield buildingCoordinateRetryClaimService.retry(claim, errorType.name(), count);
            }
            case UNLIMITED -> buildingCoordinateRetryClaimService.retry(
                claim, errorType.name(), count);
            case NONE -> {
                status = "FAILED";
                failureReason = errorType.name();
                yield buildingCoordinateRetryClaimService.fail(claim, errorType.name(), failureReason, count);
            }
        };
        if (saved) {
            structuredLogger.coordinateRetryResult(claim.buildingId(), claim.version(), status, count,
                errorType.name(), failureReason);
        }
        return errorType.isStopsRound() ? Outcome.STOP_ROUND : Outcome.CONTINUE;
    }

    public enum Outcome {
        NO_WORK, CONTINUE, STOP_ROUND
    }
}
