package com.homes.zipsai.building.domain;

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
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import com.homes.zipsai.global.domain.BaseTimeEntity;
import com.homes.zipsai.user.domain.User;

import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(
    name = "Rooms",
    uniqueConstraints = @UniqueConstraint(name = "uk_rooms_building_room_no", columnNames = {"building_id", "room_no"})
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Room extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "room_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "building_id", nullable = false)
    private Building building;

    // 입주민, 공실이면 null
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", unique = true)
    private User resident;

    @Enumerated(EnumType.STRING)
    @Column(name = "room_status", nullable = false, length = 20)
    private RoomStatus status;

    @Column(name = "room_no", nullable = false, length = 5)
    private String roomNo;

    @Builder
    public Room(Building building, String roomNo) {
        this.building = building;
        this.roomNo = roomNo;
        this.status = RoomStatus.EMPTY;
    }

    public void invite() {
        if (status != RoomStatus.EMPTY) {
            throw new IllegalStateException("공실만 초대할 수 있습니다.");
        }
        this.status = RoomStatus.INVITED;
    }

    public void moveIn(User resident) {
        if (status != RoomStatus.INVITED) {
            throw new IllegalStateException("초대 중인 호실만 입주 처리할 수 있습니다.");
        }
        this.resident = resident;
        this.status = RoomStatus.LIVING;
    }
}
