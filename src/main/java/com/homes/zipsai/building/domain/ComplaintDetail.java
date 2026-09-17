package com.homes.zipsai.building.domain;

import java.time.OffsetDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

import com.homes.zipsai.global.domain.BaseTimeEntity;

import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "Complaint_Details")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ComplaintDetail extends BaseTimeEntity {

    // 민원 ID를 PK로 공유한다.
    @Id
    private Long id;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "complaint_id")
    private Complaint complaint;

    // AI 추출 발생 위치
    @Column(name = "location", nullable = false, length = 50)
    private String location;

    // AI 추출 증상
    @Column(name = "symptom", nullable = false, length = 100)
    private String symptom;

    // AI 추출 발생 시점
    @Column(name = "occurred_time")
    private OffsetDateTime occurredTime;

    // AI 생성 민원 요약
    @Column(name = "ai_summary", nullable = false, length = 200)
    private String aiSummary;

    // 민원 처리 과정 코멘트
    @Column(name = "comment", length = 200)
    private String comment;

    @Builder
    public ComplaintDetail(Complaint complaint, String location, String symptom, OffsetDateTime occurredTime,
                           String aiSummary) {
        this.complaint = complaint;
        this.location = location;
        this.symptom = symptom;
        this.occurredTime = occurredTime;
        this.aiSummary = aiSummary;
    }
}
