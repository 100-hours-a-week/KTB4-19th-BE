package com.homes.zipsai.conversation.domain;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.homes.zipsai.global.domain.BaseTimeEntity;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "Chatbot_reply_outbox")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatbotReplyOutbox extends BaseTimeEntity {

    private static final int FIRST_ATTEMPT = 0;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "event_id")
    private Long id;

    @Column(name = "message_id", nullable = false)
    private Long messageId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "trace_id", nullable = false, length = 36)
    private String traceId;

    @Column(name = "lock_token", nullable = false, length = 36)
    private String lockToken;

    @Column(name = "new_conversation", nullable = false)
    private boolean newConversation;

    @Column(name = "previous_last_message_at")
    private LocalDateTime previousLastMessageAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ChatbotReplyOutboxStatus status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "processing_started_at", nullable = false)
    private LocalDateTime processingStartedAt;

    private ChatbotReplyOutbox(Long messageId, Long userId, String traceId, String lockToken, boolean newConversation,
                               LocalDateTime previousLastMessageAt, LocalDateTime processingStartedAt) {
        this.messageId = messageId;
        this.userId = userId;
        this.traceId = traceId;
        this.lockToken = lockToken;
        this.newConversation = newConversation;
        this.previousLastMessageAt = previousLastMessageAt;
        this.status = ChatbotReplyOutboxStatus.PROCESSING;
        this.attemptCount = FIRST_ATTEMPT;
        this.processingStartedAt = processingStartedAt;
    }

    public static ChatbotReplyOutbox startFirstMessage(Message residentMessage, Long userId, String traceId,
                                                       String lockToken, LocalDateTime now) {
        return new ChatbotReplyOutbox(residentMessage.getId(), userId, traceId, lockToken, true, null, now);
    }

    public static ChatbotReplyOutbox startNextMessage(Message residentMessage, Long userId, String traceId,
                                                      String lockToken, LocalDateTime previousLastMessageAt,
                                                      LocalDateTime now) {
        return new ChatbotReplyOutbox(residentMessage.getId(), userId, traceId, lockToken, false,
            previousLastMessageAt, now);
    }

    public boolean isProcessingBy(int attempt) {
        return status == ChatbotReplyOutboxStatus.PROCESSING && attemptCount == attempt;
    }

    public boolean isProcessingBy(int attempt, String lockToken) {
        return isProcessingBy(attempt) && this.lockToken.equals(lockToken);
    }

    public void retry(int attemptCount) {
        if (status != ChatbotReplyOutboxStatus.PROCESSING) {
            throw new IllegalStateException("Reply is not processing");
        }
        this.attemptCount = attemptCount;
        this.status = ChatbotReplyOutboxStatus.PENDING;
    }

    public void claim(String lockToken, LocalDateTime now) {
        if (status != ChatbotReplyOutboxStatus.PENDING) {
            throw new IllegalStateException("Reply is not pending");
        }
        Objects.requireNonNull(lockToken, "lockToken");
        Objects.requireNonNull(now, "now");
        this.lockToken = lockToken;
        this.processingStartedAt = now;
        this.status = ChatbotReplyOutboxStatus.PROCESSING;
    }

    public LocalDateTime requestDeadline(Duration requestTimeout, Conversation conversation) {
        LocalDateTime deadline = getCreatedAt().plus(requestTimeout);
        OffsetDateTime closesAt = conversation.closesAt();
        if (closesAt != null && closesAt.toLocalDateTime().isBefore(deadline)) {
            return closesAt.toLocalDateTime();
        }
        return deadline;
    }

    public LocalDateTime processingDeadline(Duration attemptTimeout, Duration requestTimeout,
                                             Conversation conversation) {
        LocalDateTime deadline = requestDeadline(requestTimeout, conversation);
        LocalDateTime processingDeadline = processingStartedAt.plus(attemptTimeout);
        return processingDeadline.isBefore(deadline) ? processingDeadline : deadline;
    }

    public void complete() {
        this.status = ChatbotReplyOutboxStatus.COMPLETED;
    }

    public void fail() {
        this.status = ChatbotReplyOutboxStatus.FAILED;
    }
}
