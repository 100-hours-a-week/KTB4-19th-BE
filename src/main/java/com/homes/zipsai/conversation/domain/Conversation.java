package com.homes.zipsai.conversation.domain;

import java.time.LocalDateTime;

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

import com.homes.zipsai.global.domain.BaseTimeEntity;
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

    // 질의는 첫 메시지 앞부분, 민원은 AI 요약 제목
    @Column(name = "conversation_title", length = 100)
    private String title;

    // 최근 대화 정렬 및 미응답 대화 종료 추적 용도
    @Column(name = "last_message_at")
    private LocalDateTime lastMessageAt;

    @Builder
    public Conversation(User user, ConversationType type, String title) {
        this.user = user;
        this.type = type;
        this.status = ConversationStatus.ACTIVE;
        this.title = title;
    }
}
