package com.homes.zipsai.conversation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import com.homes.zipsai.conversation.ai.AiComplaintState;

class ChatbotReplyOutboxWorkerTest {

    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 10, 23, 10, 0);
    private static final Duration ATTEMPT_TIMEOUT = Duration.ofSeconds(130);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(280);
    private static final String INITIAL_TOKEN = "initial-token";
    private static final String NEXT_TOKEN = "next-token";

    @ParameterizedTest
    @CsvSource({"0, initial-token, true", "0, other-token, false", "1, initial-token, false"})
    @DisplayName("처리 중인 실행은 실패 횟수와 락 토큰이 모두 일치해야 한다")
    void requiresMatchingFailureCountAndToken(int attempt, String token, boolean expected) {
        ChatbotReplyOutbox outbox = outbox();

        assertThat(outbox.isProcessingBy(attempt, token)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    @DisplayName("재시도 대기는 지정된 실패 횟수를 반영하고 토큰과 시작 시각을 유지한다")
    void waitsForRetryWithSelectedFailureCount(int failureCount) {
        ChatbotReplyOutbox outbox = outbox();

        outbox.retry(failureCount);

        assertThat(outbox.getStatus()).isEqualTo(ChatbotReplyOutboxStatus.PENDING);

        assertThat(outbox.getAttemptCount()).isEqualTo(failureCount);

        assertThat(outbox.getLockToken()).isEqualTo(INITIAL_TOKEN);

        assertThat(outbox.getProcessingStartedAt()).isEqualTo(CREATED_AT);

        assertThat(outbox.isProcessingBy(failureCount, INITIAL_TOKEN)).isFalse();
    }

    @Test
    @DisplayName("인계는 토큰과 시작 시각만 교체하고 실패 횟수와 전체 마감을 유지한다")
    void transfersOwnershipWithoutResettingFailureCountOrRequestDeadline() {
        ChatbotReplyOutbox outbox = outbox();
        Conversation conversation = conversation(CREATED_AT);
        outbox.retry(1);
        LocalDateTime startedAt = CREATED_AT.plusSeconds(100);

        outbox.claim(NEXT_TOKEN, startedAt);

        assertThat(outbox.getStatus()).isEqualTo(ChatbotReplyOutboxStatus.PROCESSING);

        assertThat(outbox.getAttemptCount()).isEqualTo(1);

        assertThat(outbox.getLockToken()).isEqualTo(NEXT_TOKEN);

        assertThat(outbox.getProcessingStartedAt()).isEqualTo(startedAt);

        assertThat(outbox.isProcessingBy(1, INITIAL_TOKEN)).isFalse();

        assertThat(outbox.isProcessingBy(1, NEXT_TOKEN)).isTrue();

        assertThat(outbox.requestDeadline(REQUEST_TIMEOUT, conversation))
            .isEqualTo(CREATED_AT.plusSeconds(280));
    }

    @ParameterizedTest
    @EnumSource(value = ChatbotReplyOutboxStatus.class, names = {"COMPLETED", "FAILED"})
    @DisplayName("완료하거나 실패한 작업은 재시도 대기나 새 실행으로 되돌리지 않는다")
    void preservesTerminalState(ChatbotReplyOutboxStatus status) {
        ChatbotReplyOutbox outbox = outbox();
        if (status == ChatbotReplyOutboxStatus.COMPLETED) {
            outbox.complete();
        } else {
            outbox.fail();
        }

        assertThatThrownBy(() -> outbox.retry(1)).isInstanceOf(IllegalStateException.class);

        assertThatThrownBy(() -> outbox.claim(NEXT_TOKEN, CREATED_AT.plusSeconds(100)))
            .isInstanceOf(IllegalStateException.class);

        assertThat(outbox.getStatus()).isEqualTo(status);

        assertThat(outbox.isProcessingBy(0, INITIAL_TOKEN)).isFalse();
    }

    @ParameterizedTest
    @CsvSource({"0, 280", "-60, 240"})
    @DisplayName("유효 마감은 전체 요청 상한과 기존 대화의 5분 종료 시각 중 이른 시각이다")
    void usesEarlierRequestOrConversationDeadline(int lastMessageOffset, int expectedOffset) {
        ChatbotReplyOutbox outbox = outbox();
        Conversation conversation = conversation(CREATED_AT.plusSeconds(lastMessageOffset));

        assertThat(outbox.requestDeadline(REQUEST_TIMEOUT, conversation))
            .isEqualTo(CREATED_AT.plusSeconds(expectedOffset));
    }

    @Test
    @DisplayName("접수 확인 대기는 대화 종료에서 제외하되 전체 마감은 유지한다")
    void excludesConfirmationWaitFromIdleDeadline() {
        ChatbotReplyOutbox outbox = outbox();
        Conversation conversation = conversation(CREATED_AT.minusSeconds(60));
        ReflectionTestUtils.setField(conversation, "complaintState", AiComplaintState.READY_TO_CONFIRM);

        assertThat(outbox.requestDeadline(REQUEST_TIMEOUT, conversation))
            .isEqualTo(CREATED_AT.plusSeconds(280));
    }

    @ParameterizedTest
    @CsvSource({"100, 0, 230", "200, 0, 280", "100, -100, 200"})
    @DisplayName("시도 마감은 시작 후 130초와 유효 마감 중 이른 시각이다")
    void capsProcessingDeadline(int startOffset, int lastMessageOffset, int expectedOffset) {
        ChatbotReplyOutbox outbox = outbox();
        Conversation conversation = conversation(CREATED_AT.plusSeconds(lastMessageOffset));
        outbox.retry(0);
        outbox.claim(NEXT_TOKEN, CREATED_AT.plusSeconds(startOffset));

        assertThat(outbox.processingDeadline(ATTEMPT_TIMEOUT, REQUEST_TIMEOUT, conversation))
            .isEqualTo(CREATED_AT.plusSeconds(expectedOffset));
    }

    @Test
    @DisplayName("시도와 전체 요청 마감은 고정 숫자 대신 전달된 제한시간으로 계산한다")
    void calculatesDeadlinesFromConfiguredDurations() {
        ChatbotReplyOutbox outbox = outbox();
        Conversation conversation = conversation(CREATED_AT);
        outbox.retry(0);
        outbox.claim(NEXT_TOKEN, CREATED_AT.plusSeconds(100));
        Duration attemptTimeout = Duration.ofSeconds(30);
        Duration requestTimeout = Duration.ofSeconds(200);

        assertThat(outbox.requestDeadline(requestTimeout, conversation))
            .isEqualTo(CREATED_AT.plusSeconds(200));

        assertThat(outbox.processingDeadline(attemptTimeout, requestTimeout, conversation))
            .isEqualTo(CREATED_AT.plusSeconds(130));
    }

    private static ChatbotReplyOutbox outbox() {
        Message message =
            new Message(null, "보일러가 안 켜져요", SenderType.RESIDENT, MessageType.TEXT, "turn-1");
        ReflectionTestUtils.setField(message, "id", 500L);
        ChatbotReplyOutbox outbox =
            ChatbotReplyOutbox.startFirstMessage(message, 7L, "trace-1", INITIAL_TOKEN, CREATED_AT);
        ReflectionTestUtils.setField(outbox, "createdAt", CREATED_AT);
        return outbox;
    }

    private static Conversation conversation(LocalDateTime lastMessageAt) {
        Conversation conversation = new Conversation(null, ConversationType.INQUIRY, "보일러가 안 켜져요");
        conversation.updateLastMessageAt(lastMessageAt);
        return conversation;
    }
}
