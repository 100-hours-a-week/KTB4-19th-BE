package com.homes.zipsai.conversation.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;

import com.homes.zipsai.conversation.domain.ChatbotReplyOutbox;
import com.homes.zipsai.conversation.domain.ChatbotReplyOutboxStatus;
import com.homes.zipsai.conversation.repository.ChatbotReplyOutboxRepository;
import com.homes.zipsai.migration.domain.OutboxOperation;
import com.homes.zipsai.migration.service.OutboxRecorder;

class ChatbotReplyOutboxCleanupTaskletTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 5, 4, 0);

    private final ChatbotReplyOutboxRepository outboxRepository = mock(ChatbotReplyOutboxRepository.class);
    private final OutboxRecorder outboxRecorder = mock(OutboxRecorder.class);
    private final StepContribution contribution = mock(StepContribution.class);
    private final ChatbotReplyOutboxCleanupTasklet tasklet = new ChatbotReplyOutboxCleanupTasklet(outboxRepository,
        outboxRecorder, ChatbotReplyOutboxStatus.COMPLETED, Duration.ofDays(7), 2);

    @Test
    @DisplayName("DB 시각에서 보관 기간을 뺀 시각 이전의 요청을 찾아 지우고 마이그레이션 삭제 이벤트를 남긴다")
    void deletesExpiredRequestsAndRecordsMigrationDelete() {
        given(outboxRepository.currentTime()).willReturn(NOW);
        given(outboxRepository.findExpiredIdsForUpdate("COMPLETED", NOW.minusDays(7), 2)).willReturn(List.of(3L));

        RepeatStatus status = tasklet.execute(contribution, null);

        assertThat(status).isEqualTo(RepeatStatus.FINISHED);

        then(outboxRepository).should().deleteAllByEventIds(List.of(3L));

        then(outboxRecorder).should().recordAll(ChatbotReplyOutbox.class, List.of(3L), OutboxOperation.DELETE);

        then(contribution).should().incrementWriteCount(1);
    }

    @Test
    @DisplayName("한 번에 지우는 건수만큼 지웠으면 남은 요청을 지우도록 다시 실행한다")
    void continuesWhenBatchIsFull() {
        given(outboxRepository.currentTime()).willReturn(NOW);
        given(outboxRepository.findExpiredIdsForUpdate(anyString(), any(), anyInt())).willReturn(List.of(1L, 2L));

        assertThat(tasklet.execute(contribution, null)).isEqualTo(RepeatStatus.CONTINUABLE);
    }

    @Test
    @DisplayName("지울 요청이 없으면 삭제와 마이그레이션 기록 없이 끝낸다")
    void finishesWithoutDeletingWhenNothingExpired() {
        given(outboxRepository.currentTime()).willReturn(NOW);
        given(outboxRepository.findExpiredIdsForUpdate(anyString(), any(), anyInt())).willReturn(List.of());

        assertThat(tasklet.execute(contribution, null)).isEqualTo(RepeatStatus.FINISHED);

        then(outboxRepository).should(never()).deleteAllByEventIds(any());

        then(outboxRecorder).shouldHaveNoInteractions();
    }
}
