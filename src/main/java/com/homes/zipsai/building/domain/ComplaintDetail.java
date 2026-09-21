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

    @Id
    private Long id;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "complaint_id")
    private Complaint complaint;

    @Column(name = "location", nullable = false, length = 50)
    private String location;

    @Column(name = "symptom", nullable = false, length = 100)
    private String symptom;

    @Column(name = "occurred_time")
    private OffsetDateTime occurredTime;

    @Column(name = "ai_summary", nullable = false, length = 200)
    private String aiSummary;

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
