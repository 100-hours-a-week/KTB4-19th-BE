package com.homes.zipsai.user.domain;

import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import com.homes.zipsai.global.domain.BaseTimeEntity;

import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(
    name = "Users",
    uniqueConstraints = @UniqueConstraint(name = "uk_users_email", columnNames = "email")
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "user_id")
    private Long id;

    @Column(name = "email", nullable = false, length = 254)
    private String email;

    @Column(name = "password", nullable = false, length = 60)
    private String password;

    @Column(name = "user_name", length = 7)
    private String userName;

    @Column(name = "phone", length = 13)
    private String phone;

    @Enumerated(EnumType.STRING)
    @Column(name = "user_role", nullable = false, length = 10)
    private UserRole role;

    @Enumerated(EnumType.STRING)
    @Column(name = "user_status", nullable = false, length = 10)
    private UserStatus status;

    // 역할 변경 시 증가시켜 기존 access token을 무효화한다.
    @Column(name = "auth_version", nullable = false)
    private long authVersion;

    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL)
    private List<UserAgreement> agreements = new ArrayList<>();

    @Builder
    public User(String email, String password, String userName, String phone) {
        this.email = email;
        this.password = password;
        this.userName = userName;
        this.phone = phone;
        this.role = UserRole.NONE;
        this.status = UserStatus.ACTIVE;
    }

    public void selectRole(UserRole role) {
        this.role = role;
        this.authVersion++;
    }

    public void changeUserName(String userName) {
        this.userName = userName;
    }

    public void changePhone(String phone) {
        this.phone = phone;
    }

    public void agree(Terms terms, boolean agreed) {
        agreements.add(new UserAgreement(this, terms, agreed));
    }
}
