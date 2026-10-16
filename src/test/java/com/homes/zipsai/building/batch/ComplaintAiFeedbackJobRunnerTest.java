package com.homes.zipsai.building.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.launch.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.core.task.SyncTaskExecutor;

import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;

@ExtendWith(MockitoExtension.class)
class ComplaintAiFeedbackJobRunnerTest {

    @Mock
    JobOperator jobOperator;

    @Mock
    LockProvider lockProvider;

    @Mock
    SimpleLock lock;

    @Mock
    Job sendJob;

    @Mock
    Job deleteJob;

    private ComplaintAiFeedbackJobRunner runner;

    @BeforeEach
    void setUp() {
        runner = new ComplaintAiFeedbackJobRunner(jobOperator, lockProvider, sendJob, deleteJob,
            new SyncTaskExecutor());
    }

    @Test
    @DisplayName("배치 락을 잡으면 전송 Job을 실행하고 끝나면 락을 푼다")
    void startsSendJobAndReleasesLock() throws Exception {
        given(lockProvider.lock(any(LockConfiguration.class))).willReturn(Optional.of(lock));

        String runId = runner.startSend(1L);

        ArgumentCaptor<JobParameters> captor = ArgumentCaptor.forClass(JobParameters.class);
        verify(jobOperator).start(eq(sendJob), captor.capture());
        assertThat(captor.getValue().getString("runId")).isEqualTo(runId);
        verify(lock).unlock();
    }

    @Test
    @DisplayName("삭제 수동 실행은 삭제 Job을 실행한다")
    void startsDeleteJob() throws Exception {
        given(lockProvider.lock(any(LockConfiguration.class))).willReturn(Optional.of(lock));

        runner.startDelete(1L);

        verify(jobOperator).start(eq(deleteJob), any(JobParameters.class));
    }

    @Test
    @DisplayName("다른 배치가 실행 중이면 실행하지 않고 예외를 던진다")
    void rejectsWhenAlreadyRunning() throws Exception {
        given(lockProvider.lock(any(LockConfiguration.class))).willReturn(Optional.empty());

        assertThatThrownBy(() -> runner.startSend(1L))
            .isInstanceOf(ComplaintAiFeedbackAlreadyRunningException.class);
        verify(jobOperator, never()).start(any(Job.class), any(JobParameters.class));
    }

    @Test
    @DisplayName("Job 실행에 실패해도 락을 푼다")
    void releasesLockWhenJobFails() throws Exception {
        given(lockProvider.lock(any(LockConfiguration.class))).willReturn(Optional.of(lock));
        given(jobOperator.start(eq(sendJob), any(JobParameters.class)))
            .willThrow(new JobExecutionAlreadyRunningException("실패"));

        runner.startSend(1L);

        verify(lock).unlock();
    }
}
