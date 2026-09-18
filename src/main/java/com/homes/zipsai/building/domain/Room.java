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
import com.homes.zipsai.global.exception.ConflictException;
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
        if (status == RoomStatus.LIVING) {
            throw new ConflictException(ConflictException.Reason.ROOM_OCCUPIED);
        }
        status = RoomStatus.INVITED;
    }

    public void cancelInvitation() {
        if (status != RoomStatus.INVITED) {
            throw new ConflictException(ConflictException.Reason.INVITATION_CANCEL_NOT_ALLOWED);
        }
        status = RoomStatus.EMPTY;
    }

    public void moveIn(User resident) {
        if (resident == null || status != RoomStatus.INVITED || this.resident != null) {
            throw new ConflictException(ConflictException.Reason.ROOM_CONNECTION_CONFLICT);
        }
        this.resident = resident;
        status = RoomStatus.LIVING;
    }

    public void moveOutResident() {
        if (status != RoomStatus.LIVING || resident == null) {
            throw new ConflictException(ConflictException.Reason.RESIDENT_NOT_FOUND_IN_ROOM);
        }
        resident = null;
        status = RoomStatus.EMPTY;
    }
}
