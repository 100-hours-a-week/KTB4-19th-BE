package com.homes.zipsai.conversation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import com.homes.zipsai.common.domain.File;
import com.homes.zipsai.global.domain.BaseTimeEntity;
import com.homes.zipsai.global.util.TextUtils;

import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(
    name = "Message_file_groups",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_message_file_groups_seq",
        columnNames = {"message_id", "file_group_seq"}
    )
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MessageFileGroup extends BaseTimeEntity {

    private static final int SUMMARY_MAX_LENGTH = 500;
    private static final int OCR_TEXT_MAX_LENGTH = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "file_group_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "message_id", nullable = false)
    private Message message;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "attachment_id", nullable = false)
    private File attachment;

    @Column(name = "file_group_seq", nullable = false)
    private int fileGroupSeq;

    @Column(name = "summary", length = SUMMARY_MAX_LENGTH)
    private String summary;

    @Column(name = "ocr_text", length = OCR_TEXT_MAX_LENGTH)
    private String ocrText;

    @Builder
    public MessageFileGroup(Message message, File attachment, int fileGroupSeq) {
        this.message = message;
        this.attachment = attachment;
        this.fileGroupSeq = fileGroupSeq;
    }

    public void recordAnalysis(String summary, String ocrText) {
        this.summary = TextUtils.truncate(summary, SUMMARY_MAX_LENGTH);
        this.ocrText = TextUtils.truncate(ocrText, OCR_TEXT_MAX_LENGTH);
    }
}
