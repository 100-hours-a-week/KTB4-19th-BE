package com.homes.zipsai.building.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

import com.homes.zipsai.global.domain.BaseTimeEntity;
import com.homes.zipsai.user.domain.User;

import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "Buildings")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Building extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "building_id")
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User manager;

    @Column(name = "road_address", nullable = false, length = 200)
    private String roadAddress;

    // 건물명은 등록 화면에서 선택 입력이므로 null을 허용합니다.
    @Column(name = "building_name", length = 20)
    private String buildingName;

    @Builder
    public Building(User manager, String roadAddress, String buildingName) {
        this.manager = manager;
        this.roadAddress = roadAddress;
        this.buildingName = buildingName;
    }
}
