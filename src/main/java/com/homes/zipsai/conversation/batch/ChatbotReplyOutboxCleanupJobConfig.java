package com.homes.zipsai.conversation.batch;

import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import com.homes.zipsai.conversation.domain.ChatbotReplyOutboxStatus;
import com.homes.zipsai.conversation.repository.ChatbotReplyOutboxRepository;
import com.homes.zipsai.migration.service.OutboxRecorder;

@Configuration
@EnableConfigurationProperties(ChatbotReplyOutboxCleanupProperties.class)
public class ChatbotReplyOutboxCleanupJobConfig {

    @Bean
    public Job chatbotReplyOutboxCleanupJob(JobRepository jobRepository, Step chatbotReplyOutboxCompletedCleanupStep,
                                            Step chatbotReplyOutboxFailedCleanupStep) {
        return new JobBuilder("chatbotReplyOutboxCleanupJob", jobRepository)
                .start(chatbotReplyOutboxCompletedCleanupStep)
                .next(chatbotReplyOutboxFailedCleanupStep)
                .build();
    }

    @Bean
    public Step chatbotReplyOutboxCompletedCleanupStep(JobRepository jobRepository,
                                                       PlatformTransactionManager transactionManager,
                                                       ChatbotReplyOutboxRepository outboxRepository,
                                                       OutboxRecorder outboxRecorder,
                                                       ChatbotReplyOutboxCleanupProperties properties) {
        return new StepBuilder("chatbotReplyOutboxCompletedCleanupStep", jobRepository)
                .tasklet(new ChatbotReplyOutboxCleanupTasklet(outboxRepository, outboxRecorder,
                        ChatbotReplyOutboxStatus.COMPLETED, properties.completedRetention(),
                        properties.batchSize()), transactionManager)
                .build();
    }

    @Bean
    public Step chatbotReplyOutboxFailedCleanupStep(JobRepository jobRepository,
                                                    PlatformTransactionManager transactionManager,
                                                    ChatbotReplyOutboxRepository outboxRepository,
                                                    OutboxRecorder outboxRecorder,
                                                    ChatbotReplyOutboxCleanupProperties properties) {
        return new StepBuilder("chatbotReplyOutboxFailedCleanupStep", jobRepository)
                .tasklet(new ChatbotReplyOutboxCleanupTasklet(outboxRepository, outboxRecorder,
                        ChatbotReplyOutboxStatus.FAILED, properties.failedRetention(), properties.batchSize()),
                        transactionManager)
                .build();
    }
}
