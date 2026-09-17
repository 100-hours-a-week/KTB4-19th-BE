package com.homes.zipsai.conversation.domain;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import com.homes.zipsai.conversation.ai.AiComplaintDraft;
import com.homes.zipsai.conversation.ai.AiConversationState;
import com.homes.zipsai.conversation.ai.AiRoute;
import com.homes.zipsai.global.domain.BaseTimeEntity;
import com.homes.zipsai.global.exception.ConflictException;
import com.homes.zipsai.global.exception.ForbiddenException;
import com.homes.zipsai.global.util.TextUtils;
import com.homes.zipsai.user.domain.User;

import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "Conversations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Conversation extends BaseTimeEntity {

    private static final int LOCATION_MAX_LENGTH = 50;
    private static final int SYMPTOM_MAX_LENGTH = 100;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "conversation_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "conversation_type", nullable = false, length = 20)
    private ConversationType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "conversation_status", nullable = false, length = 20)
    private ConversationStatus status;

    @Column(name = "conversation_title", length = 100)
    private String title;

    @Column(name = "last_message_at")
    private LocalDateTime lastMessageAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "current_route", length = 20)
    private AiRoute currentRoute;

    @Enumerated(EnumType.STRING)
    @Column(name = "ai_conversation_state", length = 20)
    private AiConversationState conversationState;

    @Column(name = "draft_location", length = LOCATION_MAX_LENGTH)
    private String draftLocation;

    @Column(name = "draft_symptom", length = SYMPTOM_MAX_LENGTH)
    private String draftSymptom;

    @Column(name = "draft_occurred_at")
    private OffsetDateTime draftOccurredAt;

    @Builder
    public Conversation(User user, ConversationType type, String title) {
        this.user = user;
        this.type = type;
        this.status = ConversationStatus.ACTIVE;
        this.title = title;
    }

    public void verifyOwnedBy(Long userId) {
        if (!user.getId().equals(userId)) {
            throw new ForbiddenException();
        }
    }

    public void verifyCanSendMessage() {
        verifyActive();
        if (isReadyToConfirmComplaint()) {
            throw new ConflictException(ConflictException.Reason.CONVERSATION_AWAITING_CONFIRMATION);
        }
    }

    public void verifyCanCreateComplaint() {
        if (status == ConversationStatus.COMPLAINT_CREATED) {
            throw new ConflictException(ConflictException.Reason.COMPLAINT_ALREADY_CREATED);
        }
        verifyActive();
        if (!isReadyToConfirmComplaint()) {
            throw new ConflictException(ConflictException.Reason.COMPLAINT_NOT_READY);
        }
    }

    public void updateLastMessageAt(LocalDateTime messageAt) {
        this.lastMessageAt = messageAt;
    }

    public void applyAiResponse(AiRoute route, AiConversationState nextState, AiComplaintDraft draft) {
        if (!isActive()) {
            return;
        }
        this.currentRoute = route;
        this.conversationState = nextState;
        if (draft != null) {
            storeDraft(draft);
        }
    }

    public AiComplaintDraft currentDraft() {
        return new AiComplaintDraft(draftLocation, draftSymptom, draftOccurredAt);
    }

    public boolean isReadyToConfirmComplaint() {
        return currentRoute == AiRoute.COMPLAINT && conversationState == AiConversationState.READY_TO_CONFIRM;
    }

    public void resolve() {
        if (!isActive()) {
            return;
        }
        this.status = ConversationStatus.RESOLVED;
        this.currentRoute = null;
        this.conversationState = null;
    }

    public void markComplaintCreated(String complaintTitle, AiComplaintDraft confirmedDraft) {
        this.type = ConversationType.COMPLAINT;
        this.status = ConversationStatus.COMPLAINT_CREATED;
        this.title = complaintTitle;
        this.currentRoute = null;
        this.conversationState = null;
        storeDraft(confirmedDraft);
    }

    private void verifyActive() {
        if (!isActive()) {
            throw new ConflictException(ConflictException.Reason.CONVERSATION_CLOSED);
        }
    }

    private boolean isActive() {
        return status == ConversationStatus.ACTIVE;
    }

    private void storeDraft(AiComplaintDraft draft) {
        this.draftLocation = TextUtils.truncate(draft.location(), LOCATION_MAX_LENGTH);
        this.draftSymptom = TextUtils.truncate(draft.symptom(), SYMPTOM_MAX_LENGTH);
        this.draftOccurredAt = draft.occurredAt();
    }
}
