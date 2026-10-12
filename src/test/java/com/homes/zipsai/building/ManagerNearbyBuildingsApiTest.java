package com.homes.zipsai.building;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.homes.zipsai.building.domain.Building;
import com.homes.zipsai.building.domain.BuildingLocation;
import com.homes.zipsai.building.domain.Room;
import com.homes.zipsai.building.domain.RoomStatus;
import com.homes.zipsai.user.domain.User;
import com.homes.zipsai.user.domain.UserRole;

import tools.jackson.databind.JsonNode;

class ManagerNearbyBuildingsApiTest extends NearbyBuildingIntegrationTestSupport {

    @Test
    @DisplayName("범위 안의 본인 건물과 타 건물만 좌표 및 호실 요약으로 반환한다")
    void returnsOwnAndOtherRegisteredBuildingsWithinBounds() throws Exception {
        room(ownBuilding, "101", RoomStatus.EMPTY);
        room(ownBuilding, "102", RoomStatus.INVITED);
        room(ownBuilding, "103", RoomStatus.LIVING);
        Building other = locatedBuilding("타인건물", 37.6, 127.6);
        room(other, "201", RoomStatus.LIVING);
        locatedBuilding("범위밖", 36.9, 127.5);
        building(user(UserRole.MANAGER), "좌표없음");
        Building deleted = locatedBuilding("삭제건물", 37.7, 127.7);
        softDelete(deleted);

        JsonNode data = success(bounds());

        assertThat(data.size()).isEqualTo(1);

        assertThat(ids(data)).containsExactlyInAnyOrder(ownBuilding.getId(), other.getId());

        assertBuilding(item(data, ownBuilding), ownBuilding, 37.5, 127.5, 3, 1, 2);

        assertBuilding(item(data, other), other, 37.6, 127.6, 1, 1, 0);
    }

    @Test
    @DisplayName("범위에 건물이 없으면 200과 빈 buildings 배열을 반환한다")
    void returnsEmptyListWhenNoBuildingIsWithinBounds() throws Exception {
        JsonNode data = success(bounds(35, 36, 125, 126));

        assertThat(data.path("buildings").isArray()).isTrue();

        assertThat(data.path("buildings").size()).isZero();
    }

    @Test
    @DisplayName("본인 건물의 좌표가 없어도 타 건물 요약을 조회할 수 있다")
    void allowsManagerWhoseOwnBuildingHasNoCoordinates() throws Exception {
        entityManager.remove(entityManager.find(BuildingLocation.class, ownBuilding.getId()));
        Building other = locatedBuilding("타인건물", 37.6, 127.6);

        assertThat(ids(success(bounds()))).containsExactly(other.getId());
    }

    @Test
    @DisplayName("인증하지 않은 사용자는 401 공통 오류로 거부한다")
    void rejectsUnauthenticatedUser() throws Exception {
        mvc.perform(bounds()).andExpect(status().isUnauthorized())

            .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))

            .andExpect(jsonPath("$.data").value((Object) null));
    }

    @Test
    @DisplayName("입주민은 403 공통 오류로 거부한다")
    void rejectsResident() throws Exception {
        User resident = user(UserRole.RESIDENT);

        mvc.perform(bounds().with(as(resident))).andExpect(status().isForbidden())

            .andExpect(jsonPath("$.error.code").value("FORBIDDEN"))

            .andExpect(jsonPath("$.data").value((Object) null));
    }

    @Test
    @DisplayName("자기 건물이 미등록 또는 삭제된 관리자는 BUILDING_NOT_FOUND로 거부한다")
    void rejectsManagerWithoutActiveBuilding() throws Exception {
        User unregistered = user(UserRole.MANAGER);
        softDelete(ownBuilding);
        entityManager.flush();
        for (User rejected : List.of(manager, unregistered)) {
            mvc.perform(bounds().with(as(rejected))).andExpect(status().isNotFound())

                .andExpect(jsonPath("$.error.code").value("BUILDING_NOT_FOUND"))

                .andExpect(jsonPath("$.data").value((Object) null));
        }
    }

    @Test
    @DisplayName("타 건물 요약을 조회해도 타인 호실의 퇴거 요청은 거부한다")
    void allowsOtherBuildingSummaryWithoutGrantingRoomAccess() throws Exception {
        Building other = locatedBuilding("타인건물", 37.6, 127.6);
        Room otherRoom = room(other, "201", RoomStatus.LIVING);

        assertThat(ids(success(bounds()))).contains(other.getId());

        mvc.perform(delete("/api/v1/managers/me/rooms/" + otherRoom.getId() + "/resident").with(as(manager)))
            .andExpect(status().isForbidden())

            .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

}
