package com.homes.zipsai.common.domain;

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
@Table(name = "Files")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class File extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "attachment_id")
    private Long id;

    @Column(name = "file_key", nullable = false, length = 255)
    private String fileKey;

    @Column(name = "file_size", nullable = false)
    private int fileSize;

    // jpg, png, pdf
    @Column(name = "file_type", nullable = false, length = 50)
    private String fileType;

    @Column(name = "original_name", nullable = false, length = 255)
    private String originalName;

    @Enumerated(EnumType.STRING)
    @Column(name = "file_status", nullable = false, length = 20)
    private FileStatus status;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User owner;

    @Builder
    public File(String fileKey, int fileSize, String fileType, String originalName) {
        this.fileKey = fileKey;
        this.fileSize = fileSize;
        this.fileType = fileType;
        this.originalName = originalName;
        this.status = FileStatus.PENDING;
    }

    public void markUploaded(int actualSize, String actualType) {
        this.fileSize = actualSize;
        this.fileType = actualType;
        this.status = FileStatus.UPLOADED;
    }

    public void assignOwner(User owner) {
        this.owner = owner;
    }
}
