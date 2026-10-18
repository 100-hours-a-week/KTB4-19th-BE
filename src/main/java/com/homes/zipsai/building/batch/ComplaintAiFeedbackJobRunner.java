package com.homes.zipsai.building.batch;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecutionException;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;

import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;

@Component
public class ComplaintAiFeedbackJobRunner {

    public static final String LOCK_NAME = "complaintAiFeedbackJob";

    private static final Logger LOGGER = LoggerFactory.getLogger(ComplaintAiFeedbackJobRunner.class);
    private static final Duration LOCK_AT_MOST_FOR = Duration.ofMinutes(30);

    private final JobOperator jobOperator;
    private final LockProvider lockProvider;
    private final Job sendJob;
    private final Job deleteJob;
    private final TaskExecutor taskExecutor;

    @Autowired
    public ComplaintAiFeedbackJobRunner(
            JobOperator jobOperator,
            LockProvider lockProvider,
            @Qualifier("complaintAiFeedbackSendJob") Job sendJob,
            @Qualifier("complaintAiFeedbackDeleteJob") Job deleteJob) {
        this(jobOperator, lockProvider, sendJob, deleteJob, virtualThreadExecutor());
    }

    ComplaintAiFeedbackJobRunner(JobOperator jobOperator, LockProvider lockProvider, Job sendJob, Job deleteJob,
                                 TaskExecutor taskExecutor) {
        this.jobOperator = jobOperator;
        this.lockProvider = lockProvider;
        this.sendJob = sendJob;
        this.deleteJob = deleteJob;
        this.taskExecutor = taskExecutor;
    }

    public String startSend(Long operatorId) {
        return start(sendJob, operatorId);
    }

    public String startDelete(Long operatorId) {
        return start(deleteJob, operatorId);
    }

    private String start(Job job, Long operatorId) {
        SimpleLock lock = lockProvider.lock(new LockConfiguration(Instant.now(), LOCK_NAME, LOCK_AT_MOST_FOR,
                        Duration.ZERO))
                .orElseThrow(ComplaintAiFeedbackAlreadyRunningException::new);
        String runId = UUID.randomUUID().toString();
        LOGGER.info("민원 답변 AI 피드백 배치를 수동 실행합니다. job={}, runId={}, operatorId={}",
                job.getName(), runId, operatorId);
        try {
            taskExecutor.execute(() -> run(job, runId, lock));
        } catch (RuntimeException exception) {
            lock.unlock();
            throw exception;
        }
        return runId;
    }

    private void run(Job job, String runId, SimpleLock lock) {
        try {
            jobOperator.start(job, new JobParametersBuilder().addString("runId", runId).toJobParameters());
        } catch (JobExecutionException exception) {
            LOGGER.error("민원 답변 AI 피드백 배치 수동 실행에 실패했습니다. job={}, runId={}",
                    job.getName(), runId, exception);
        } finally {
            lock.unlock();
        }
    }

    private static TaskExecutor virtualThreadExecutor() {
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("complaint-ai-feedback-manual-");
        executor.setVirtualThreads(true);
        return executor;
    }
}
