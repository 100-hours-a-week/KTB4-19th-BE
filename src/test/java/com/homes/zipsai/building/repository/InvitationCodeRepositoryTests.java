package com.homes.zipsai.building.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import com.homes.zipsai.building.domain.Building;
import com.homes.zipsai.building.domain.InvitationCode;
import com.homes.zipsai.building.domain.Room;
import com.homes.zipsai.user.domain.User;

@DataJpaTest
class InvitationCodeRepositoryTests {
    @Autowired
    EntityManager entityManager;

    @Autowired
    RoomRepository rooms;

    @Autowired
    InvitationCodeRepository codes;

    @Test
    void findsManagerOwnedRoomAndActiveInvitationCode() {
        User manager = new User("manager@example.com", "password", "관리자", null);
        entityManager.persist(manager);
        Building building = new Building(manager, "서울시", "A타워");
        entityManager.persist(building);
        Room room = new Room(building, "101호");
        entityManager.persist(room);
        InvitationCode code = new InvitationCode(room, "ABC234", LocalDateTime.now().plusHours(3));
        entityManager.persist(code);
        entityManager.flush();
        entityManager.clear();

        assertThat(rooms.findByIdWithBuildingAndManager(room.getId()))
                .isPresent()
                .get()
                .extracting(Room::getRoomNo)
                .isEqualTo("101호");
        assertThat(codes.findActiveByRoomId(room.getId())).hasSize(1);
    }
}
