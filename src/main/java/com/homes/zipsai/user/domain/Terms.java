package com.homes.zipsai.user.domain;

import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import com.homes.zipsai.global.domain.BaseTimeEntity;

import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(
    name = "Terms",
    uniqueConstraints = @UniqueConstraint(name = "uk_terms_type_version", columnNames = {"terms_type", "version"})
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Terms extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "terms_id")
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "terms_type", nullable = false, length = 20)
    private TermsType termsType;

    @Column(name = "version", nullable = false)
    private int version;

    @Column(name = "title", nullable = false, length = 100)
    private String title;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "effective_at", nullable = false)
    private LocalDate effectiveAt;

    @Builder
    public Terms(TermsType termsType, int version, String title, String content, LocalDate effectiveAt) {
        this.termsType = termsType;
        this.version = version;
        this.title = title;
        this.content = content;
        this.effectiveAt = effectiveAt;
    }
}
