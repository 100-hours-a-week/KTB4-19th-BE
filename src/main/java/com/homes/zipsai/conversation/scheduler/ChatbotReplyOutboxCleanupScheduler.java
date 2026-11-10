package com.homes.zipsai.conversation.scheduler;

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

import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

@Component
@ConditionalOnProperty(name = "app.conversation.reply-outbox-cleanup.enabled", havingValue = "true")
@RequiredArgsConstructor
public class ChatbotReplyOutboxCleanupScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ChatbotReplyOutboxCleanupScheduler.class);

    private final JobOperator jobOperator;
    private final Job chatbotReplyOutboxCleanupJob;

    @Scheduled(cron = "0 0 18 * * *", zone = "Asia/Seoul")
    @SchedulerLock(name = "chatbotReplyOutboxCleanup", lockAtLeastFor = "1m", lockAtMostFor = "30m")
    public void cleanUpChatbotReplyOutbox() {
        try {
            jobOperator.start(chatbotReplyOutboxCleanupJob, new JobParametersBuilder()
                    .addLocalDate("targetDate", LocalDate.now())
                    .toJobParameters());
        } catch (JobExecutionException exception) {
            LOGGER.error("챗봇 답변 아웃박스 정리 배치 실행에 실패했습니다.", exception);
        }
    }
}
