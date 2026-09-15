package com.homes.zipsai.building.domain;

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
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

import com.homes.zipsai.common.domain.File;
import com.homes.zipsai.conversation.domain.Conversation;
import com.homes.zipsai.global.domain.BaseTimeEntity;
import com.homes.zipsai.user.domain.User;

import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "Complaints")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Complaint extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "complaint_id")
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "conversation_id", nullable = false, unique = true)
    private Conversation conversation;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "building_id", nullable = false)
    private Building building;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "attachment_id", unique = true)
    private File attachment;

    @Column(name = "title", nullable = false, length = 50)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "complaint_status", nullable = false, length = 20)
    private ComplaintStatus status;

    // AI가 민원 생성 시 산정한 긴급도 (0~10)
    @Column(name = "urgency", nullable = false, columnDefinition = "TINYINT")
    private int urgency;

    @Column(name = "room_no", nullable = false, length = 5)
    private String roomNo;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @Builder
    public Complaint(Conversation conversation, User user, Building building, File attachment,
                     String title, int urgency, String roomNo) {
        this.conversation = conversation;
        this.user = user;
        this.building = building;
        this.attachment = attachment;
        this.title = title;
        this.status = ComplaintStatus.PENDING;
        this.urgency = urgency;
        this.roomNo = roomNo;
    }
}
