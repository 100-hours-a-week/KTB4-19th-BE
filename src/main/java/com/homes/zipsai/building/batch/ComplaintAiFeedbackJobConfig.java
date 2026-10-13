package com.homes.zipsai.building.batch;

import java.time.Duration;
import java.time.LocalDateTime;

import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.batch.infrastructure.item.ItemReader;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.transaction.PlatformTransactionManager;

import com.homes.zipsai.building.domain.Complaint;
import com.homes.zipsai.building.domain.ComplaintDetail;
import com.homes.zipsai.building.repository.ComplaintDetailRepository;
import com.homes.zipsai.conversation.ai.AiComplaintFeedbackClient;
import com.homes.zipsai.conversation.ai.AiComplaintFeedbackRequest;
import com.homes.zipsai.global.exception.AiUnavailableException;

@Configuration
public class ComplaintAiFeedbackJobConfig {

    private static final int CHUNK_SIZE = 100;
    private static final int MAX_BATCH_SIZE = 5000;
    private static final long RETRY_LIMIT = 3;
    private static final Duration INITIAL_DELAY = Duration.ofSeconds(1);
    private static final double DELAY_MULTIPLIER = 2.0;
    private static final Duration MAX_DELAY = Duration.ofSeconds(4);

    @Bean
    public Job complaintAiFeedbackJob(JobRepository jobRepository, Step complaintAiFeedbackStep) {
        return new JobBuilder("complaintAiFeedbackJob", jobRepository)
                .start(complaintAiFeedbackStep)
                .build();
    }

    @Bean
    public Step complaintAiFeedbackStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            ItemReader<ComplaintDetail> complaintAiFeedbackReader,
            ItemWriter<FeedbackItem> complaintAiFeedbackWriter) {
        return new StepBuilder("complaintAiFeedbackStep", jobRepository)
                .<ComplaintDetail, FeedbackItem>chunk(CHUNK_SIZE)
                .reader(complaintAiFeedbackReader)
                .processor(complaintAiFeedbackProcessor())
                .writer(complaintAiFeedbackWriter)
                .transactionManager(transactionManager)
                .faultTolerant()
                .retryPolicy(retryPolicy())
                .build();
    }

    @Bean
    @StepScope
    public ItemReader<ComplaintDetail> complaintAiFeedbackReader(ComplaintDetailRepository complaintDetailRepository) {
        return new ComplaintAiFeedbackItemReader(complaintDetailRepository, MAX_BATCH_SIZE);
    }

    private ItemProcessor<ComplaintDetail, FeedbackItem> complaintAiFeedbackProcessor() {
        return detail -> {
            Complaint complaint = detail.getComplaint();
            String issueType = complaint.getConversation() != null
                    ? complaint.getConversation().getDraftIssueType()
                    : null;
            AiComplaintFeedbackRequest request = new AiComplaintFeedbackRequest(
                    complaint.getBuilding().getId(),
                    complaint.getType() != null ? complaint.getType().name() : null,
                    issueType,
                    detail.getLocation(),
                    detail.getSymptom(),
                    detail.getOccurredTime(),
                    detail.getComment(),
                    detail.getUpdatedAt());
            return new FeedbackItem(detail, request);
        };
    }

    @Bean
    public ItemWriter<FeedbackItem> complaintAiFeedbackWriter(
            AiComplaintFeedbackClient client, ComplaintDetailRepository complaintDetailRepository) {
        return (Chunk<? extends FeedbackItem> chunk) -> {
            LocalDateTime now = LocalDateTime.now();
            for (FeedbackItem item : chunk) {
                client.send(item.request());
                item.detail().markAiFeedbackSent(now);
                complaintDetailRepository.save(item.detail());
            }
        };
    }

    private RetryPolicy retryPolicy() {
        return RetryPolicy.builder()
                .maxRetries(RETRY_LIMIT)
                .delay(INITIAL_DELAY)
                .multiplier(DELAY_MULTIPLIER)
                .maxDelay(MAX_DELAY)
                .includes(AiUnavailableException.class)
                .build();
    }

    private record FeedbackItem(ComplaintDetail detail, AiComplaintFeedbackRequest request) {
    }
}
