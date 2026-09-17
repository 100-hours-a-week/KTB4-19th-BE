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

    @Builder
    public MessageFileGroup(Message message, File attachment, int fileGroupSeq) {
        this.message = message;
        this.attachment = attachment;
        this.fileGroupSeq = fileGroupSeq;
    }
}
