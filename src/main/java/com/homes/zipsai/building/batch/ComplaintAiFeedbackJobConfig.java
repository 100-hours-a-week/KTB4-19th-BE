package com.homes.zipsai.building.batch;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
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
import com.homes.zipsai.conversation.ai.AiComplaintFeedbackResponse;
import com.homes.zipsai.conversation.ai.AiRetryLaterException;
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
    private static final Duration MAX_RETRY_AFTER_WAIT = Duration.ofSeconds(30);

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
            Step complaintAiFeedbackWorkerStep,
            ComplaintDetailRepository complaintDetailRepository,
            @Value("${app.complaint.ai-feedback.concurrency:1}") int concurrency) {
        int gridSize = Math.max(1, concurrency);
        return new StepBuilder("complaintAiFeedbackStep", jobRepository)
                .partitioner("complaintAiFeedbackWorkerStep",
                        new ComplaintAiFeedbackPartitioner(complaintDetailRepository, MAX_BATCH_SIZE))
                .step(complaintAiFeedbackWorkerStep)
                .gridSize(gridSize)
                .taskExecutor(partitionTaskExecutor(gridSize))
                .build();
    }

    private static TaskExecutor partitionTaskExecutor(int concurrency) {
        if (concurrency == 1) {
            return new SyncTaskExecutor();
        }
        SimpleAsyncTaskExecutor taskExecutor = new SimpleAsyncTaskExecutor("complaint-ai-feedback-");
        taskExecutor.setVirtualThreads(true);
        taskExecutor.setConcurrencyLimit(concurrency);
        return taskExecutor;
    }

    @Bean
    public Step complaintAiFeedbackWorkerStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            ItemReader<ComplaintDetail> complaintAiFeedbackReader,
            ItemWriter<FeedbackItem> complaintAiFeedbackWriter) {
        return new StepBuilder("complaintAiFeedbackWorkerStep", jobRepository)
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
    public ItemReader<ComplaintDetail> complaintAiFeedbackReader(
            ComplaintDetailRepository complaintDetailRepository,
            @Value("#{stepExecutionContext['" + ComplaintAiFeedbackPartitioner.MIN_ID + "']}") long minId,
            @Value("#{stepExecutionContext['" + ComplaintAiFeedbackPartitioner.MAX_ID + "']}") long maxId,
            @Value("#{stepExecutionContext['" + ComplaintAiFeedbackPartitioner.LIMIT + "']}") int limit) {
        return new ComplaintAiFeedbackItemReader(complaintDetailRepository, minId, maxId, limit);
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
            AiComplaintFeedbackResponse response = waitIfAskedToRetryLater(
                    () -> client.send(new AiComplaintFeedbackBatchRequest(traceId, requests)));

            ResultTally tally = new ResultTally();
            for (FeedbackItem item : chunk) {
                if (tally.handled(response, item.id(), item.updatedAt(), traceId, "전송")) {
                    complaintDetailRepository.markAiFeedbackSent(item.id(), item.updatedAt());
                }
            }
            LOGGER.info("민원 답변 피드백을 AI에 전송했습니다. traceId={}, items={}, {}", traceId, requests.size(), tally);
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
            AiComplaintFeedbackResponse response = waitIfAskedToRetryLater(
                    () -> client.delete(new AiComplaintFeedbackDeleteRequest(traceId, items)));

            ResultTally tally = new ResultTally();
            int handled = 0;
            int marked = 0;
            for (AiFeedbackDeletionTarget target : chunk) {
                if (tally.handled(response, target.complaintDetailId(), target.updatedAt(), traceId, "삭제")) {
                    handled++;
                    marked += complaintDetailRepository.markAiFeedbackDeleted(
                            target.complaintDetailId(), target.updatedAt());
                }
            }
            LOGGER.info("삭제된 민원 답변을 AI에서 제거했습니다. traceId={}, items={}, changedDuringDelete={}, {}",
                    traceId, items.size(), handled - marked, tally);
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

    /**
     * AI가 429·503과 Retry-After로 잠시 뒤 다시 보내라고 하면, 청크 재시도 전에 그 시간(최대 30초)만큼 기다린다.
     * 기다린 뒤 예외를 다시 던져 기존 재시도 규칙(1초 → 2초 → 4초, 3번)을 그대로 따른다.
     */
    private static AiComplaintFeedbackResponse waitIfAskedToRetryLater(
            Supplier<AiComplaintFeedbackResponse> call) {
        try {
            return call.get();
        } catch (AiRetryLaterException exception) {
            Duration retryAfter = exception.retryAfter();
            if (retryAfter != null && !retryAfter.isZero()) {
                Duration wait = retryAfter.compareTo(MAX_RETRY_AFTER_WAIT) > 0 ? MAX_RETRY_AFTER_WAIT : retryAfter;
                LOGGER.warn("AI가 잠시 뒤 다시 보내라고 응답해 {}초 기다린 뒤 재시도합니다.", wait.toSeconds());
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            throw exception;
        }
    }

    private static final class ResultTally {

        private int succeeded;
        private int skipped;
        private int failed;
        private int missing;
        private int versionMismatch;
        private int acceptedWithoutResult;

        boolean handled(AiComplaintFeedbackResponse response, long complaintDetailId, LocalDateTime sentVersion,
                        String traceId, String action) {
            if (response == null || response.results().isEmpty()) {
                acceptedWithoutResult++;
                return true;
            }
            Optional<AiComplaintFeedbackResponse.Result> found = response.find(complaintDetailId);
            if (found.isEmpty()) {
                missing++;
                LOGGER.warn("AI 응답에 민원 답변 {} 결과가 없어 다음 실행에서 다시 보냅니다. traceId={}, complaintDetailId={}",
                        action, traceId, complaintDetailId);
                return false;
            }
            AiComplaintFeedbackResponse.Result result = found.get();
            OffsetDateTime expected = TimeUtils.toOffsetDateTime(sentVersion);
            if (result.updatedAt() != null && !result.updatedAt().isEqual(expected)) {
                versionMismatch++;
                LOGGER.warn("AI 응답의 민원 답변 {} 버전이 보낸 버전과 달라 다음 실행에서 다시 보냅니다. traceId={}, "
                                + "complaintDetailId={}, sent={}, received={}",
                        action, traceId, complaintDetailId, expected, result.updatedAt());
                return false;
            }
            if (AiComplaintFeedbackResponse.STATUS_SUCCEEDED.equals(result.status())) {
                succeeded++;
                return true;
            }
            if (AiComplaintFeedbackResponse.STATUS_SKIPPED.equals(result.status())) {
                skipped++;
                LOGGER.info("AI가 민원 답변 {} 요청을 반영하지 않았습니다. traceId={}, complaintDetailId={}, reasonCode={}",
                        action, traceId, complaintDetailId, result.reasonCode());
                return true;
            }
            if (Boolean.FALSE.equals(result.retryable())) {
                skipped++;
                LOGGER.warn("AI가 다시 보내도 같은 결과라고 알려 와 민원 답변 {} 요청을 다시 보내지 않습니다. traceId={}, "
                                + "complaintDetailId={}, status={}, reasonCode={}",
                        action, traceId, complaintDetailId, result.status(), result.reasonCode());
                return true;
            }
            failed++;
            LOGGER.warn("AI가 민원 답변 {} 처리에 실패해 다음 실행에서 다시 보냅니다. traceId={}, complaintDetailId={}, "
                            + "status={}, reasonCode={}",
                    action, traceId, complaintDetailId, result.status(), result.reasonCode());
            return false;
        }

        @Override
        public String toString() {
            return "succeeded=" + succeeded + ", skipped=" + skipped + ", failed=" + failed + ", missing=" + missing
                    + ", versionMismatch=" + versionMismatch + ", acceptedWithoutResult=" + acceptedWithoutResult;
        }
    }
}
