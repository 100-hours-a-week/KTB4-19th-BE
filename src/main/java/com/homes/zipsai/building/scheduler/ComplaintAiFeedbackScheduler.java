package com.homes.zipsai.building.scheduler;

import java.time.LocalDate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecutionException;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.homes.zipsai.building.batch.ComplaintAiFeedbackJobRunner;

import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

@Component
@ConditionalOnProperty(name = "app.complaint.ai-feedback-scheduler.enabled", havingValue = "true",
    matchIfMissing = false)
@RequiredArgsConstructor
public class ComplaintAiFeedbackScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ComplaintAiFeedbackScheduler.class);

    private final JobOperator jobOperator;
    private final Job complaintAiFeedbackJob;

    @Scheduled(cron = "0 0 22 * * *", zone = "Asia/Seoul")
    @SchedulerLock(name = ComplaintAiFeedbackJobRunner.LOCK_NAME, lockAtLeastFor = "1m", lockAtMostFor = "30m")
    public void runDailyComplaintAiFeedbackJob() {
        try {
            jobOperator.start(complaintAiFeedbackJob, new JobParametersBuilder()
                    .addLocalDate("targetDate", LocalDate.now())
                    .toJobParameters());
        } catch (JobExecutionException exception) {
            LOGGER.error("민원 답변 AI 피드백 배치 실행에 실패했습니다.", exception);
        }
    }
}
