package com.homes.zipsai.conversation.batch;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;

import com.homes.zipsai.conversation.domain.ChatbotReplyOutbox;
import com.homes.zipsai.conversation.domain.ChatbotReplyOutboxStatus;
import com.homes.zipsai.conversation.repository.ChatbotReplyOutboxRepository;
import com.homes.zipsai.migration.domain.OutboxOperation;
import com.homes.zipsai.migration.service.OutboxRecorder;

public class ChatbotReplyOutboxCleanupTasklet implements Tasklet {

    private final ChatbotReplyOutboxRepository outboxRepository;
    private final OutboxRecorder outboxRecorder;
    private final ChatbotReplyOutboxStatus status;
    private final Duration retention;
    private final int batchSize;

    public ChatbotReplyOutboxCleanupTasklet(ChatbotReplyOutboxRepository outboxRepository,
                                            OutboxRecorder outboxRecorder, ChatbotReplyOutboxStatus status,
                                            Duration retention, int batchSize) {
        this.outboxRepository = outboxRepository;
        this.outboxRecorder = outboxRecorder;
        this.status = status;
        this.retention = retention;
        this.batchSize = batchSize;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        LocalDateTime updatedBefore = outboxRepository.currentTime().minus(retention);
        List<Long> eventIds = outboxRepository.findExpiredIdsForUpdate(status.name(), updatedBefore, batchSize);
        if (eventIds.isEmpty()) {
            return RepeatStatus.FINISHED;
        }
        outboxRepository.deleteAllByEventIds(eventIds);
        outboxRecorder.recordAll(ChatbotReplyOutbox.class, eventIds, OutboxOperation.DELETE);
        contribution.incrementWriteCount(eventIds.size());
        return eventIds.size() < batchSize ? RepeatStatus.FINISHED : RepeatStatus.CONTINUABLE;
    }
}
