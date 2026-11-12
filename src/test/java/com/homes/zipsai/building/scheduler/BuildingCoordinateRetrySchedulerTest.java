package com.homes.zipsai.building.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.homes.zipsai.building.service.BuildingCoordinateRetryWorker;
import com.homes.zipsai.building.service.BuildingCoordinateRetryWorker.Outcome;

class BuildingCoordinateRetrySchedulerTest {

    private final BuildingCoordinateRetryWorker buildingCoordinateRetryWorker =
        mock(BuildingCoordinateRetryWorker.class);
    private final BuildingCoordinateRetryScheduler buildingCoordinateRetryScheduler =
        new BuildingCoordinateRetryScheduler(buildingCoordinateRetryWorker);

    @Test
    @DisplayName("한 회차에서 다음 작업을 실행 직전에 하나씩 요청하며 실행 가능한 작업을 모두 처리한다")
    void drainsWorkSequentiallyWithoutRoundLimit() {
        AtomicInteger executions = new AtomicInteger();
        when(buildingCoordinateRetryWorker.executeNext()).thenAnswer(invocation ->
            executions.incrementAndGet() <= 100 ? Outcome.CONTINUE : Outcome.NO_WORK);

        buildingCoordinateRetryScheduler.retryCoordinates();

        verify(buildingCoordinateRetryWorker, times(101)).executeNext();
    }

    @ParameterizedTest
    @EnumSource(value = Outcome.class, names = {"NO_WORK", "STOP_ROUND"})
    @DisplayName("작업이 없거나 회차 중단 결과를 받으면 더 실행하지 않고 다음 회차에서 다시 조회한다")
    void resumesNextRoundAfterStopping(Outcome outcome) {
        when(buildingCoordinateRetryWorker.executeNext()).thenReturn(outcome, Outcome.CONTINUE, Outcome.NO_WORK);

        buildingCoordinateRetryScheduler.retryCoordinates();

        verify(buildingCoordinateRetryWorker).executeNext();

        buildingCoordinateRetryScheduler.retryCoordinates();

        verify(buildingCoordinateRetryWorker, times(3)).executeNext();
    }

    @Test
    @DisplayName("같은 인스턴스의 실행 중인 회차와 새 회차는 중첩되지 않고 완료 후 다시 실행된다")
    void preventsOverlappingRoundsAndReleasesGuard() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximumActive = new AtomicInteger();
        when(buildingCoordinateRetryWorker.executeNext()).thenAnswer(invocation -> {
            maximumActive.accumulateAndGet(active.incrementAndGet(), Math::max);
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("실행 해제 시간 초과");
                }
                return Outcome.NO_WORK;
            } finally {
                active.decrementAndGet();
            }
        });
        try (ExecutorService executorService = Executors.newFixedThreadPool(2)) {
            Future<?> first = executorService.submit(buildingCoordinateRetryScheduler::retryCoordinates);
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

                Future<?> overlapping = executorService.submit(buildingCoordinateRetryScheduler::retryCoordinates);
                overlapping.get(2, TimeUnit.SECONDS);

                verify(buildingCoordinateRetryWorker).executeNext();
            } finally {
                release.countDown();
            }
            first.get(5, TimeUnit.SECONDS);
        }
        buildingCoordinateRetryScheduler.retryCoordinates();

        assertThat(maximumActive.get()).isEqualTo(1);

        verify(buildingCoordinateRetryWorker, times(2)).executeNext();
    }

    @Test
    @DisplayName("한 인스턴스가 실행 중이어도 다른 인스턴스는 자기 작업을 처리한다")
    void allowsAnotherInstanceToRunIndependently() throws Exception {
        BuildingCoordinateRetryWorker otherBuildingCoordinateRetryWorker = mock(BuildingCoordinateRetryWorker.class);
        BuildingCoordinateRetryScheduler otherBuildingCoordinateRetryScheduler =
            new BuildingCoordinateRetryScheduler(otherBuildingCoordinateRetryWorker);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(buildingCoordinateRetryWorker.executeNext()).thenAnswer(invocation -> {
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("실행 해제 시간 초과");
            }
            return Outcome.STOP_ROUND;
        });
        when(otherBuildingCoordinateRetryWorker.executeNext()).thenReturn(Outcome.CONTINUE, Outcome.NO_WORK);
        try (ExecutorService executorService = Executors.newSingleThreadExecutor()) {
            Future<?> first = executorService.submit(buildingCoordinateRetryScheduler::retryCoordinates);
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

                otherBuildingCoordinateRetryScheduler.retryCoordinates();

                verify(otherBuildingCoordinateRetryWorker, times(2)).executeNext();
            } finally {
                release.countDown();
            }
            first.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("회차 실행 중 예외가 전파돼도 실행 중 표시를 해제해 다음 회차를 허용한다")
    void releasesGuardAfterUnexpectedException() {
        IllegalStateException exception = new IllegalStateException("예상하지 못한 중단");
        when(buildingCoordinateRetryWorker.executeNext()).thenThrow(exception).thenReturn(Outcome.NO_WORK);

        assertThatThrownBy(buildingCoordinateRetryScheduler::retryCoordinates).isSameAs(exception);

        buildingCoordinateRetryScheduler.retryCoordinates();

        verify(buildingCoordinateRetryWorker, times(2)).executeNext();
    }

    @Test
    @DisplayName("워커가 강제 중단돼도 실행 중 표시를 해제해 새 회차를 허용한다")
    void releasesGuardAfterWorkerAborts() {
        AssertionError error = new AssertionError("워커 중단");
        when(buildingCoordinateRetryWorker.executeNext()).thenThrow(error).thenReturn(Outcome.NO_WORK);

        assertThatThrownBy(buildingCoordinateRetryScheduler::retryCoordinates).isSameAs(error);

        buildingCoordinateRetryScheduler.retryCoordinates();

        verify(buildingCoordinateRetryWorker, times(2)).executeNext();
    }

    @Test
    @DisplayName("스레드가 이미 중단됐으면 작업을 요청하지 않고 중단 표시도 유지한다")
    void skipsAlreadyInterruptedThread() {
        try {
            Thread.currentThread().interrupt();
            buildingCoordinateRetryScheduler.retryCoordinates();

            verifyNoInteractions(buildingCoordinateRetryWorker);

            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    @DisplayName("작업 사이에 스레드가 중단되면 추가 선점을 요청하지 않고 다음 정상 회차는 실행한다")
    void stopsBetweenJobsAndResumesAfterInterruption() {
        when(buildingCoordinateRetryWorker.executeNext()).thenAnswer(invocation -> {
            Thread.currentThread().interrupt();
            return Outcome.CONTINUE;
        }).thenReturn(Outcome.NO_WORK);
        try {
            buildingCoordinateRetryScheduler.retryCoordinates();

            verify(buildingCoordinateRetryWorker).executeNext();

            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
        buildingCoordinateRetryScheduler.retryCoordinates();

        verify(buildingCoordinateRetryWorker, times(2)).executeNext();
    }
}
