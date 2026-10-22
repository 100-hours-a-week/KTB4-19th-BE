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

    private static Message residentMessage(long id) {
        Message message = new Message(null, "보일러가 안 켜져요", SenderType.RESIDENT, MessageType.TEXT, "turn-1");
        ReflectionTestUtils.setField(message, "id", id);
        return message;
    }
}
