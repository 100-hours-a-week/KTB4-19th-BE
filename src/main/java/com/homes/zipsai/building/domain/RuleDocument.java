package com.homes.zipsai.building.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import com.homes.zipsai.common.domain.File;
import com.homes.zipsai.global.domain.BaseTimeEntity;

import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "Rule_Documents")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RuleDocument extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "document_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "building_id", nullable = false)
    private Building building;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "attachment_id", nullable = false)
    private File attachment;

    @Column(name = "document_title", nullable = false, length = 20)
    private String title;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "version", nullable = false)
    private int version;

    @Column(name = "is_valid", nullable = false)
    private boolean valid;

    @Builder
    public RuleDocument(Building building, File attachment, String title, String content, int version) {
        this.building = building;
        this.attachment = attachment;
        this.title = title;
        this.content = content;
        this.version = version;
        this.valid = true;
    }

    public void updateTitle(String title) {
        this.title = title;
        this.version++;
    }

    public void replaceAttachment(File attachment) {
        this.attachment = attachment;
    }

    public void delete() {
        this.valid = false;
    }
}
