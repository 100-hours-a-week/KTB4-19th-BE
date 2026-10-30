package com.homes.zipsai.conversation.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ChatbotReplyOutboxTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 22, 10, 5);
    private static final LocalDateTime PREVIOUS_LAST_MESSAGE_AT = LocalDateTime.of(2026, 10, 22, 10, 0);

    @Test
    @DisplayName("첫 메시지의 답변 이벤트는 첫 시도로 처리 중이고 새 대화방으로 표시된다")
    void startsFirstMessageAsProcessingNewConversation() {
        Message residentMessage = residentMessage(500L);

        ChatbotReplyOutbox outbox = ChatbotReplyOutbox.startFirstMessage(residentMessage, 7L, "trace-1", "lock-1", NOW);

        assertThat(outbox)
            .hasFieldOrPropertyWithValue("messageId", 500L)
            .hasFieldOrPropertyWithValue("userId", 7L)
            .hasFieldOrPropertyWithValue("traceId", "trace-1")
            .hasFieldOrPropertyWithValue("lockToken", "lock-1")
            .hasFieldOrPropertyWithValue("newConversation", true)
            .hasFieldOrPropertyWithValue("previousLastMessageAt", null)
            .hasFieldOrPropertyWithValue("status", ChatbotReplyOutboxStatus.PROCESSING)
            .hasFieldOrPropertyWithValue("attemptCount", 0)
            .hasFieldOrPropertyWithValue("processingStartedAt", NOW);
    }

    @Test
    @DisplayName("후속 메시지의 답변 이벤트는 실패하면 되돌릴 마지막 메시지 시각을 함께 남긴다")
    void startsNextMessageWithPreviousLastMessageAt() {
        Message residentMessage = residentMessage(502L);

        ChatbotReplyOutbox outbox = ChatbotReplyOutbox.startNextMessage(
            residentMessage, 7L, "trace-2", "lock-2", PREVIOUS_LAST_MESSAGE_AT, NOW);

        assertThat(outbox)
            .hasFieldOrPropertyWithValue("messageId", 502L)
            .hasFieldOrPropertyWithValue("newConversation", false)
            .hasFieldOrPropertyWithValue("previousLastMessageAt", PREVIOUS_LAST_MESSAGE_AT)
            .hasFieldOrPropertyWithValue("status", ChatbotReplyOutboxStatus.PROCESSING)
            .hasFieldOrPropertyWithValue("attemptCount", 0)
            .hasFieldOrPropertyWithValue("processingStartedAt", NOW);
    }

    @Test
    @DisplayName("시도 번호가 다르면 처리 중인 이벤트로 보지 않는다")
    void isNotProcessingByAnotherAttempt() {
        ChatbotReplyOutbox outbox =
            ChatbotReplyOutbox.startFirstMessage(residentMessage(500L), 7L, "trace-1", "lock-1", NOW);

        assertThat(outbox.isProcessingBy(0)).isTrue();
        assertThat(outbox.isProcessingBy(1)).isFalse();
    }

    @Test
    @DisplayName("끝난 이벤트는 같은 시도 번호여도 처리 중으로 보지 않는다")
    void isNotProcessingAfterCompleted() {
        ChatbotReplyOutbox outbox =
            ChatbotReplyOutbox.startFirstMessage(residentMessage(500L), 7L, "trace-1", "lock-1", NOW);

        outbox.complete();

        assertThat(outbox.isProcessingBy(0)).isFalse();
    }

    @Test
    @DisplayName("대기 중인 이벤트는 같은 시도 번호의 발행 확인을 받으면 발행 시각을 남긴다")
    void marksPublishedWhenPendingWithSameAttempt() {
        ChatbotReplyOutbox outbox = pendingOutbox(0);

        boolean marked = outbox.markPublished(0, NOW);

        assertThat(marked).isTrue();
        assertThat(outbox.getPublishedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("이미 발행 시각이 있으면 다시 기록하지 않는다")
    void doesNotOverwritePublishedAt() {
        ChatbotReplyOutbox outbox = pendingOutbox(0);
        outbox.markPublished(0, NOW);

        boolean marked = outbox.markPublished(0, NOW.plusSeconds(5));

        assertThat(marked).isFalse();
        assertThat(outbox.getPublishedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("이전 시도의 늦은 발행 확인은 재시도 대기 중인 이벤트에 기록하지 않는다")
    void ignoresLatePublishConfirmOfPreviousAttempt() {
        ChatbotReplyOutbox outbox = pendingOutbox(1);

        boolean marked = outbox.markPublished(0, NOW);

        assertThat(marked).isFalse();
        assertThat(outbox.getPublishedAt()).isNull();
    }

    @Test
    @DisplayName("처리 중인 이벤트에는 발행 시각을 기록하지 않는다")
    void doesNotMarkPublishedWhileProcessing() {
        ChatbotReplyOutbox outbox =
            ChatbotReplyOutbox.startFirstMessage(residentMessage(500L), 7L, "trace-1", "lock-1", NOW);

        boolean marked = outbox.markPublished(0, NOW);

        assertThat(marked).isFalse();
        assertThat(outbox.getPublishedAt()).isNull();
    }

    @Test
    @DisplayName("재시도로 대기 상태가 되면 발행 시각을 비워 다시 발행 대상이 된다")
    void clearsPublishedAtOnRetry() {
        ChatbotReplyOutbox outbox = pendingOutbox(0);
        outbox.markPublished(0, NOW);
        outbox.claim("lock-2", NOW.plusSeconds(1));
        outbox.recordFailure();

        outbox.retry(outbox.getAttemptCount());

        assertThat(outbox.getStatus()).isEqualTo(ChatbotReplyOutboxStatus.PENDING);
        assertThat(outbox.getAttemptCount()).isEqualTo(1);
        assertThat(outbox.getPublishedAt()).isNull();
    }

    private static ChatbotReplyOutbox pendingOutbox(int attempt) {
        ChatbotReplyOutbox outbox =
            ChatbotReplyOutbox.startFirstMessage(residentMessage(500L), 7L, "trace-1", "lock-1", NOW);
        outbox.retry(attempt);
        return outbox;
    }

    private static Message residentMessage(long id) {
        Message message = new Message(null, "보일러가 안 켜져요", SenderType.RESIDENT, MessageType.TEXT, "turn-1");
        ReflectionTestUtils.setField(message, "id", id);
        return message;
    }
}
