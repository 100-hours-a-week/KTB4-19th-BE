package com.homes.zipsai.user.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import com.homes.zipsai.global.domain.BaseTimeEntity;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "User_agreements")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserAgreement extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "agreement_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "terms_id", nullable = false)
    private Terms terms;

    @Column(name = "is_agreed", nullable = false)
    private boolean agreed;

    @Column(name = "agreed_at")
    private LocalDateTime agreedAt;

    public UserAgreement(User user, Terms terms, boolean agreed) {
        this.user = user;
        this.terms = terms;
        this.agreed = agreed;
        this.agreedAt = agreed ? LocalDateTime.now() : null;
    }

    public TermsType getTermsType() {
        return terms.getTermsType();
    }
}
