package com.homes.zipsai.migration.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.annotation.Scheduled;

import com.homes.zipsai.migration.service.OutboxRelayService;
import com.homes.zipsai.migration.service.OutboxWorkerMetrics;

@ExtendWith(MockitoExtension.class)
class OutboxRelaySchedulerTest {

    @Mock
    OutboxRelayService outboxRelayService;

    @Mock
    OutboxWorkerMetrics outboxWorkerMetrics;

    @InjectMocks
    OutboxRelayScheduler outboxRelayScheduler;

    @Test
    @DisplayName("이벤트가 더 남아 있으면 기다리지 않고 바로 다음 묶음을 반영한다")
    void relaysNextBatchWithoutWaitingWhileEventsRemain() {
        given(outboxRelayService.relayPendingEvents(any())).willReturn(true, true, false);

        outboxRelayScheduler.relayPendingEvents();

        then(outboxRelayService).should(times(3)).relayPendingEvents(any());
    }

    @Test
    @DisplayName("건수 로그와 지표 갱신은 이벤트 반영과 다른 스케줄러에서 실행한다")
    void runsCountsOnSchedulerOtherThanRelay() throws NoSuchMethodException {
        String relayScheduler = schedulerOf("relayPendingEvents");

        assertThat(schedulerOf("logUnprocessedCounts")).isNotEqualTo(relayScheduler);
        assertThat(schedulerOf("refreshMetrics")).isNotEqualTo(relayScheduler);
    }

    private String schedulerOf(String methodName) throws NoSuchMethodException {
        return OutboxRelayScheduler.class.getMethod(methodName).getAnnotation(Scheduled.class).scheduler();
    }
}
