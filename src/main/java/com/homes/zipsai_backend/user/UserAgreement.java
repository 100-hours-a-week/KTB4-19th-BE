package com.homes.zipsai_backend.user;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.AccessLevel;

@Entity @Table(name = "User_agreements") @Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserAgreement {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "agreement_id") private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "user_id") private User user;
    @Enumerated(EnumType.STRING) @Column(name = "terms_type", nullable = false, length = 10) private TermsType termsType;
    @Column(name = "is_agreed", nullable = false) private boolean agreed;
    @Column(name = "agreed_at") private LocalDateTime agreedAt;
    @Column(name = "created_at", nullable = false) private LocalDateTime createdAt;
    @Column(name = "updated_at", nullable = false) private LocalDateTime updatedAt;
    @Column(name = "deleted_at") private LocalDateTime deletedAt;
    public UserAgreement(User user, TermsType type, boolean agreed) {
        this.user = user; this.termsType = type; this.agreed = agreed;
        createdAt = LocalDateTime.now(); updatedAt = createdAt; agreedAt = agreed ? createdAt : null;
    }
}
