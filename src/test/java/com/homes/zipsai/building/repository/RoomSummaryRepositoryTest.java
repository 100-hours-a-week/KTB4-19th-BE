package com.homes.zipsai.building.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.util.ReflectionTestUtils;

import com.homes.zipsai.building.domain.Building;
import com.homes.zipsai.building.domain.InvitationCode;
import com.homes.zipsai.building.domain.Room;
import com.homes.zipsai.building.domain.RoomStatus;
import com.homes.zipsai.building.dto.BuildingRoomCounts;
import com.homes.zipsai.user.domain.User;
import com.homes.zipsai.user.domain.UserRole;

@DataJpaTest(showSql = false, properties = {
    "spring.datasource.url=jdbc:tc:mysql:8.4.11:///zipsai_nearby_repositories?TC_DAEMON=true",
    "spring.datasource.username=test",
    "spring.datasource.password=test",
    "spring.test.database.replace=none",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.jpa.hibernate.naming.physical-strategy=org.hibernate.boot.model.naming.PhysicalNamingStrategyStandardImpl",
    "spring.jpa.defer-datasource-initialization=false",
    "spring.flyway.enabled=true"
})
class RoomSummaryRepositoryTest {

    @Autowired
    EntityManager entityManager;

    @Autowired
    RoomRepository repository;

    private Building building;

    @BeforeEach
    void setUp() {
        building = building();
    }

    @Test
    @DisplayName("선택한 활성 건물만 집계하고 만료된 초대중 호실도 공실로 세며 삭제 호실을 제외한다")
    void groupsActiveRoomsAndCountsInvitationsAsEmpty() {
        room(building, "101", RoomStatus.EMPTY);
        Room invited = room(building, "102", RoomStatus.INVITED);
        InvitationCode expired = new InvitationCode(invited, "123456", LocalDateTime.now().minusDays(1));
        expired.expire();
        entityManager.persist(expired);
        Room activeInvitation = room(building, "103", RoomStatus.INVITED);
        entityManager.persist(new InvitationCode(activeInvitation, "654321", LocalDateTime.now().plusDays(1)));
        room(building, "104", RoomStatus.LIVING);
        softDelete(room(building, "105", RoomStatus.EMPTY));
        softDelete(room(building, "106", RoomStatus.INVITED));
        softDelete(room(building, "107", RoomStatus.LIVING));
        Building other = building();
        room(other, "101", RoomStatus.LIVING);
        Building excluded = building();
        room(excluded, "101", RoomStatus.LIVING);
        Building deleted = building();
        room(deleted, "101", RoomStatus.LIVING);
        softDelete(deleted);
        flushAndClear();

        var found = repository.summarizeByBuildingIds(List.of(building.getId(), other.getId(), deleted.getId()));

        assertThat(found).extracting(BuildingRoomCounts::buildingId)
            .containsExactlyInAnyOrder(building.getId(), other.getId());

        assertThat(found).filteredOn(row -> row.buildingId().equals(building.getId())).singleElement()
            .satisfies(row -> {
                assertThat(row.totalCount()).isEqualTo(4);

                assertThat(row.livingCount()).isEqualTo(1);

                assertThat(row.emptyCount()).isEqualTo(3);
            });

        assertThat(found).filteredOn(row -> row.buildingId().equals(other.getId())).singleElement()
            .satisfies(row -> {
                assertThat(row.totalCount()).isEqualTo(1);

                assertThat(row.livingCount()).isEqualTo(1);

                assertThat(row.emptyCount()).isZero();
            });

        assertThat(entityManager.find(Room.class, invited.getId()).getStatus()).isEqualTo(RoomStatus.INVITED);
    }

    @Test
    @DisplayName("선택한 건물의 호실이 없으면 집계 행을 반환하지 않는다")
    void returnsNoAggregateRowForBuildingWithoutRooms() {
        flushAndClear();

        assertThat(repository.summarizeByBuildingIds(List.of(building.getId()))).isEmpty();
    }

    @Test
    @DisplayName("선택한 건물의 호실이 모두 삭제됐으면 집계 행을 반환하지 않는다")
    void excludesBuildingWithOnlyDeletedRooms() {
        softDelete(room(building, "101", RoomStatus.LIVING));
        flushAndClear();

        assertThat(repository.summarizeByBuildingIds(List.of(building.getId()))).isEmpty();
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    private Building building() {
        User manager = user(UserRole.MANAGER);
        Building building = new Building(manager, "서울 강남구 테스트로 1", "집계건물");
        entityManager.persist(building);
        return building;
    }

    private User user(UserRole role) {
        User user = new User(UUID.randomUUID() + "@example.com", "password", "사용자", null);
        user.selectRole(role);
        entityManager.persist(user);
        return user;
    }

    private Room room(Building owner, String number, RoomStatus status) {
        Room room = new Room(owner, number);
        if (status == RoomStatus.INVITED || status == RoomStatus.LIVING) {
            room.invite();
        }
        if (status == RoomStatus.LIVING) {
            room.moveIn(user(UserRole.RESIDENT));
        }
        entityManager.persist(room);
        return room;
    }

    private void softDelete(Object entity) {
        ReflectionTestUtils.setField(entity, "deletedAt", LocalDateTime.now());
    }
}
