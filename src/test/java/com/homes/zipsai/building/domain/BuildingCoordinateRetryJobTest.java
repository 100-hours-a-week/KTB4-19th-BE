package com.homes.zipsai.building.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class BuildingCoordinateRetryJobTest {

    private final Building building = new Building(null, "서울시 테스트로 1", "테스트빌");
    private final LocalDateTime nextRetry = LocalDateTime.of(2026, 10, 16, 12, 0);

    @Test
    @DisplayName("신규 작업은 재시도 횟수와 선점 버전을 소모하지 않고 대기 상태로 생성된다")
    void createsPendingJobWithoutConsumingRetryOrClaim() {
        BuildingCoordinateRetryJob job = new BuildingCoordinateRetryJob(building, "E".repeat(30), nextRetry);

        assertThat(job.getBuilding()).isSameAs(building);

        assertThat(job.getStatus()).isEqualTo(BuildingCoordinateRetryJobStatus.PENDING);

        assertThat(job.getRetryFailureCount()).isZero();

        assertThat(job.getClaimVersion()).isZero();

        assertThat(job.getNextRetryAt()).isEqualTo(nextRetry);

        assertThat(job.getLeaseExpiresAt()).isNull();

        assertThat(job.getLastErrorType()).isEqualTo("E".repeat(30));

        assertThat(job.getFailureReason()).isNull();
    }

    @Test
    @DisplayName("건물이 없으면 좌표 재시도 작업을 생성할 수 없다")
    void rejectsMissingBuilding() {
        assertThatThrownBy(() -> new BuildingCoordinateRetryJob(null, "NETWORK_ERROR", nextRetry))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("다음 실행 시각이 없으면 좌표 재시도 작업을 생성할 수 없다")
    void rejectsMissingNextRetry() {
        assertThatThrownBy(() -> new BuildingCoordinateRetryJob(building, "NETWORK_ERROR", null))
            .isInstanceOf(NullPointerException.class);
    }

    @ParameterizedTest
    @DisplayName("마지막 오류 분류가 없거나 공백 또는 30자를 초과하면 작업 생성을 거절한다")
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t", "EEEEEEEEEEEEEEEEEEEEEEEEEEEEEEE"})
    void rejectsMissingOrOversizedErrorType(String error) {
        assertThatThrownBy(() -> new BuildingCoordinateRetryJob(building, error, nextRetry))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
