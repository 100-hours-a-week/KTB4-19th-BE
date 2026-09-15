package com.homes.zipsai_backend.user;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.AccessLevel;

@Entity
@Table(name = "Users", uniqueConstraints = @UniqueConstraint(name = "uk_users_email", columnNames = "email"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "user_id") private Long id;
    @Column(nullable = false, length = 254) private String email;
    @Column(nullable = false, length = 60) private String password;
    @Column(name = "user_name", length = 7) private String userName;
    @Column(length = 13) private String phone;
    @Enumerated(EnumType.STRING) @Column(name = "user_role", nullable = false, length = 10)
    private UserRole role = UserRole.NONE;
    @Enumerated(EnumType.STRING) @Column(name = "user_status", nullable = false, length = 10)
    private UserStatus status = UserStatus.ACTIVE;
    @Column(name = "auth_version", nullable = false) private long authVersion;
    @Column(name = "created_at", nullable = false) private LocalDateTime createdAt;
    @Column(name = "updated_at", nullable = false) private LocalDateTime updatedAt;
    @Column(name = "deleted_at") private LocalDateTime deletedAt;
    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL)
    private List<UserAgreement> agreements = new ArrayList<>();
    public User(String email, String password, String userName, String phone) {
        this.email = email; this.password = password; this.userName = userName; this.phone = phone;
    }
    @PrePersist void created() { createdAt = LocalDateTime.now(); updatedAt = createdAt; }
    @PreUpdate void updated() { updatedAt = LocalDateTime.now(); }
    public void selectRole(UserRole role) { this.role = role; authVersion++; }
    public void setUserName(String value) { userName = value; }
    public void setPhone(String value) { phone = value; }
    public void agree(TermsType type, boolean agreed) { agreements.add(new UserAgreement(this, type, agreed)); }
}
