package com.homes.zipsai.building.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.homes.zipsai.user.domain.User;

class BuildingCoordinateRetryClaimTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 16, 12, 0, 0, 123456000);

    @Test
    @DisplayName("실행 가능 시각과 같은 시각부터 선점하고 만료 시각은 정확히 십 분 후로 정한다")
    void claimsAtDueTime() {
        BuildingCoordinateRetryJob job = job(NOW);

        job.claim(NOW);

        assertThat(job.getStatus()).isEqualTo(BuildingCoordinateRetryJobStatus.PROCESSING);

        assertThat(job.getClaimVersion()).isEqualTo(1);

        assertThat(job.getLeaseExpiresAt()).isEqualTo(NOW.plusMinutes(10));

        assertThat(job.getNextRetryAt()).isNull();
    }

    @ParameterizedTest
    @CsvSource({"-1,true", "0,false", "1,false"})
    @DisplayName("선점 만료 시각 직전만 소유권이 유효하며 만료 시각과 이후는 거절한다")
    void checksLeaseBoundary(long microseconds, boolean expected) {
        BuildingCoordinateRetryJob job = job(NOW);
        job.claim(NOW);

        assertThat(job.isOwnedBy(1, NOW.plusMinutes(10).plusNanos(microseconds * 1000))).isEqualTo(expected);

        assertThat(job.isOwnedBy(0, NOW)).isFalse();
    }

    @Test
    @DisplayName("유효한 선점을 다시 선점할 수 없고 만료 시각부터 버전을 올려 회수한다")
    void reclaimsAtExactExpiry() {
        BuildingCoordinateRetryJob job = job(NOW);
        job.claim(NOW);

        assertThatThrownBy(() -> job.claim(NOW.plusMinutes(10).minusNanos(1000)))
            .isInstanceOf(IllegalStateException.class);

        job.claim(NOW.plusMinutes(10));

        assertThat(job.getClaimVersion()).isEqualTo(2);

        assertThat(job.getLeaseExpiresAt()).isEqualTo(NOW.plusMinutes(20));

        assertThat(job.isOwnedBy(1, NOW.plusMinutes(10))).isFalse();
    }

    @Test
    @DisplayName("다음 실행 시각 전에는 대기 작업을 선점하지 않는다")
    void rejectsClaimBeforeDueTime() {
        BuildingCoordinateRetryJob job = job(NOW.plusNanos(1000));

        assertThatThrownBy(() -> job.claim(NOW)).isInstanceOf(IllegalStateException.class);

        assertThat(job.getClaimVersion()).isZero();
    }

    private BuildingCoordinateRetryJob job(LocalDateTime next) {
        User manager = new User("claim@example.com", "password", "관리자", null);
        return new BuildingCoordinateRetryJob(new Building(manager, "서울 테스트로 1", null), "NETWORK_ERROR", next);
    }
}
