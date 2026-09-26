package com.homes.zipsai.building.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import com.homes.zipsai.building.domain.Building;
import com.homes.zipsai.building.domain.InvitationCode;
import com.homes.zipsai.building.domain.Room;
import com.homes.zipsai.user.domain.User;

@DataJpaTest
@DisplayName("초대코드 저장소")
class InvitationCodeRepositoryTests {
    @Autowired
    EntityManager entityManager;

    @Autowired
    RoomRepository rooms;

    @Autowired
    InvitationCodeRepository codes;

    @Test
    @DisplayName("활성 코드만 조회하고 코드와 소유 호실 연결을 정확히 찾는다")
    void findsActiveCodesAndAssociatedRoomOwnership() {
        User manager = new User("manager@example.com", "password", "관리자", null);
        entityManager.persist(manager);
        Building building = new Building(manager, "서울시", "A타워");
        entityManager.persist(building);
        Room room = new Room(building, "101호");
        Room otherRoom = new Room(building, "102호");
        entityManager.persist(room);
        entityManager.persist(otherRoom);

        InvitationCode active = new InvitationCode(room, "ABC234", LocalDateTime.now().plusHours(3));
        InvitationCode expired = new InvitationCode(room, "DEF567", LocalDateTime.now().plusHours(3));
        expired.expire();
        InvitationCode used = new InvitationCode(room, "GHI789", LocalDateTime.now().plusHours(3));
        used.use();
        InvitationCode deleted = new InvitationCode(room, "JKL234", LocalDateTime.now().plusHours(3));
        InvitationCode otherRoomCode = new InvitationCode(otherRoom, "MNO345", LocalDateTime.now().plusHours(3));
        entityManager.persist(active);
        entityManager.persist(expired);
        entityManager.persist(used);
        entityManager.persist(deleted);
        entityManager.persist(otherRoomCode);
        entityManager.flush();
        entityManager.createNativeQuery(
                "UPDATE Invitation_codes SET deleted_at = CURRENT_TIMESTAMP WHERE code_id = :id")
                .setParameter("id", deleted.getId())
                .executeUpdate();
        entityManager.clear();

        Room foundRoom = rooms.findByIdWithBuildingAndManager(room.getId()).orElseThrow();
        assertThat(foundRoom.getRoomNo()).isEqualTo("101호");
        assertThat(foundRoom.getBuilding().getManager().getId()).isEqualTo(manager.getId());
        assertThat(codes.findActiveByRoomId(room.getId()))
                .extracting(InvitationCode::getCode)
                .containsExactly("ABC234");
        assertThat(codes.findByIdAndRoomId(active.getId(), room.getId()))
                .isPresent()
                .get()
                .extracting(InvitationCode::getCode)
                .isEqualTo("ABC234");
        assertThat(codes.findByIdAndRoomId(active.getId(), otherRoom.getId())).isEmpty();
        assertThat(codes.findByCodeWithRoomAndBuilding("ABC234"))
                .hasValueSatisfying(found -> {
                    assertThat(found.getRoom().getRoomNo()).isEqualTo("101호");
                    assertThat(found.getRoom().getBuilding().getManager().getId()).isEqualTo(manager.getId());
                });
        assertThat(codes.findByCodeWithRoomAndBuilding("JKL234")).isEmpty();
    }
}
