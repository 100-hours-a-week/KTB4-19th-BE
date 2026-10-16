package com.homes.zipsai.building.batch;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import com.homes.zipsai.building.domain.ComplaintContent;
import com.homes.zipsai.building.domain.ComplaintDetail;
import com.homes.zipsai.building.domain.ComplaintType;
import com.homes.zipsai.building.repository.AiFeedbackDeletionTarget;
import com.homes.zipsai.building.repository.ComplaintDetailRepository;
import com.homes.zipsai.conversation.ai.AiComplaintDraft;
import com.homes.zipsai.conversation.ai.AiComplaintFeedbackBatchRequest;
import com.homes.zipsai.conversation.ai.AiComplaintFeedbackClient;
import com.homes.zipsai.conversation.ai.AiComplaintFeedbackDeleteRequest;
import com.homes.zipsai.conversation.ai.AiComplaintFeedbackRequest;
import com.homes.zipsai.global.exception.AiUnavailableException;
import com.homes.zipsai.global.util.TimeUtils;

@Configuration
public class ComplaintAiFeedbackJobConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger(ComplaintAiFeedbackJobConfig.class);

    private static final String UNKNOWN_VALUE = ComplaintContent.from(AiComplaintDraft.EMPTY).symptom();
    private static final int CHUNK_SIZE = 100;
    private static final int MAX_BATCH_SIZE = 5000;
    private static final long RETRY_LIMIT = 3;
    private static final Duration INITIAL_DELAY = Duration.ofSeconds(1);
    private static final double DELAY_MULTIPLIER = 2.0;
    private static final Duration MAX_DELAY = Duration.ofSeconds(4);

    @Bean
    public Job complaintAiFeedbackJob(JobRepository jobRepository, Step complaintAiFeedbackStep,
                                      Step complaintAiFeedbackDeleteStep) {
        return new JobBuilder("complaintAiFeedbackJob", jobRepository)
                .start(complaintAiFeedbackStep)
                .next(complaintAiFeedbackDeleteStep)
                .build();
    }

    @Bean
    public Job complaintAiFeedbackSendJob(JobRepository jobRepository, Step complaintAiFeedbackStep) {
        return new JobBuilder("complaintAiFeedbackSendJob", jobRepository)
                .start(complaintAiFeedbackStep)
                .build();
    }

    @Bean
    public Job complaintAiFeedbackDeleteJob(JobRepository jobRepository, Step complaintAiFeedbackDeleteStep) {
        return new JobBuilder("complaintAiFeedbackDeleteJob", jobRepository)
                .start(complaintAiFeedbackDeleteStep)
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
        return new ComplaintAiFeedbackItemReader(complaintDetailRepository, UNKNOWN_VALUE, MAX_BATCH_SIZE);
    }

    private ItemProcessor<ComplaintDetail, FeedbackItem> complaintAiFeedbackProcessor() {
        return detail -> {
            Complaint complaint = detail.getComplaint();
            boolean qa = complaint.getType() == ComplaintType.QA;
            String issueType = qa || complaint.getConversation() == null
                    ? null
                    : complaint.getConversation().getDraftIssueType();
            String location = qa || UNKNOWN_VALUE.equals(detail.getLocation())
                    ? null
                    : detail.getLocation();
            AiComplaintFeedbackRequest request = new AiComplaintFeedbackRequest(
                    detail.getId(),
                    complaint.getBuilding().getId(),
                    complaint.getType().name(),
                    issueType,
                    location,
                    detail.getSymptom(),
                    detail.getOccurredTime(),
                    detail.getComment(),
                    TimeUtils.toOffsetDateTime(detail.getUpdatedAt()));
            return new FeedbackItem(detail.getId(), detail.getUpdatedAt(), request);
        };
    }

    @Bean
    public ItemWriter<FeedbackItem> complaintAiFeedbackWriter(
            AiComplaintFeedbackClient client, ComplaintDetailRepository complaintDetailRepository) {
        return (Chunk<? extends FeedbackItem> chunk) -> {
            List<AiComplaintFeedbackRequest> requests = new ArrayList<>();
            for (FeedbackItem item : chunk) {
                requests.add(item.request());
            }
            String traceId = UUID.randomUUID().toString();
            client.send(new AiComplaintFeedbackBatchRequest(traceId, requests));

            for (FeedbackItem item : chunk) {
                complaintDetailRepository.markAiFeedbackSent(item.id(), item.updatedAt());
            }
            LOGGER.info("민원 답변 피드백을 AI에 전송했습니다. traceId={}, items={}", traceId, requests.size());
        };
    }

    @Bean
    public Step complaintAiFeedbackDeleteStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            ItemReader<AiFeedbackDeletionTarget> complaintAiFeedbackDeletionReader,
            ItemWriter<AiFeedbackDeletionTarget> complaintAiFeedbackDeletionWriter) {
        return new StepBuilder("complaintAiFeedbackDeleteStep", jobRepository)
                .<AiFeedbackDeletionTarget, AiFeedbackDeletionTarget>chunk(CHUNK_SIZE)
                .reader(complaintAiFeedbackDeletionReader)
                .writer(complaintAiFeedbackDeletionWriter)
                .transactionManager(transactionManager)
                .faultTolerant()
                .retryPolicy(retryPolicy())
                .build();
    }

    @Bean
    @StepScope
    public ItemReader<AiFeedbackDeletionTarget> complaintAiFeedbackDeletionReader(
            ComplaintDetailRepository complaintDetailRepository) {
        return new ComplaintAiFeedbackDeletionReader(complaintDetailRepository, MAX_BATCH_SIZE);
    }

    @Bean
    public ItemWriter<AiFeedbackDeletionTarget> complaintAiFeedbackDeletionWriter(
            AiComplaintFeedbackClient client, ComplaintDetailRepository complaintDetailRepository) {
        return (Chunk<? extends AiFeedbackDeletionTarget> chunk) -> {
            List<AiComplaintFeedbackDeleteRequest.Item> items = new ArrayList<>();
            for (AiFeedbackDeletionTarget target : chunk) {
                items.add(new AiComplaintFeedbackDeleteRequest.Item(target.buildingId(), target.complaintDetailId(),
                        TimeUtils.toOffsetDateTime(target.updatedAt())));
            }
            String traceId = UUID.randomUUID().toString();
            client.delete(new AiComplaintFeedbackDeleteRequest(traceId, items));

            int marked = 0;
            for (AiFeedbackDeletionTarget target : chunk) {
                marked += complaintDetailRepository.markAiFeedbackDeleted(
                        target.complaintDetailId(), target.updatedAt());
            }
            LOGGER.info("삭제된 민원 답변을 AI에서 제거했습니다. traceId={}, items={}, changedDuringDelete={}",
                    traceId, items.size(), items.size() - marked);
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

    private record FeedbackItem(Long id, LocalDateTime updatedAt, AiComplaintFeedbackRequest request) {
    }
}
