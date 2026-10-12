package com.homes.zipsai.building.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.homes.zipsai.building.dto.BuildingRoomCounts;
import com.homes.zipsai.building.dto.NearbyBuilding;
import com.homes.zipsai.building.dto.response.ManagerNearbyBuildingResponse;
import com.homes.zipsai.building.dto.response.NearbyRoomSummaryResponse;
import com.homes.zipsai.building.repository.BuildingLocationRepository;
import com.homes.zipsai.building.repository.BuildingRepository;
import com.homes.zipsai.building.repository.RoomRepository;
import com.homes.zipsai.global.exception.NotFoundException;

class NearbyBuildingServiceTest {

    private final BuildingRepository buildings = mock(BuildingRepository.class);
    private final BuildingLocationRepository locations = mock(BuildingLocationRepository.class);
    private final RoomRepository rooms = mock(RoomRepository.class);
    private NearbyBuildingService service;

    @BeforeEach
    void setUp() {
        service = new NearbyBuildingService(buildings, locations, rooms);
    }

    @Test
    @DisplayName("본인과 타 건물의 집계를 식별자로 연결하고 집계 없는 건물은 0으로 반환한다")
    void joinsRoomCountsByBuildingIdAndFillsMissingCountsWithZero() {
        registeredManager();
        when(locations.findWithinBounds(37, 38, 127, 128)).thenReturn(List.of(
                new NearbyBuilding(1L, "본인 건물", 37.1, 127.1),
                new NearbyBuilding(2L, "타 건물", 37.2, 127.2),
                new NearbyBuilding(3L, null, 37.3, 127.3)));
        when(rooms.summarizeByBuildingIds(List.of(1L, 2L, 3L))).thenReturn(List.of(
                new BuildingRoomCounts(2L, 7, 4, 3), new BuildingRoomCounts(1L, 3, 1, 2)));

        var response = service.getNearbyBuildings(10L, 37.0, 38.0, 127.0, 128.0);

        assertThat(response.buildings()).containsExactly(
                new ManagerNearbyBuildingResponse(1L, "본인 건물", 37.1, 127.1,
                        new NearbyRoomSummaryResponse(3, 1, 2)),
                new ManagerNearbyBuildingResponse(2L, "타 건물", 37.2, 127.2,
                        new NearbyRoomSummaryResponse(7, 4, 3)),
                new ManagerNearbyBuildingResponse(3L, null, 37.3, 127.3,
                        new NearbyRoomSummaryResponse(0, 0, 0)));

    }

    @Test
    @DisplayName("활성 등록 건물이 없는 관리자는 주변 건물 조회 전에 404로 거부한다")
    void rejectsManagerWithoutActiveBuilding() {
        when(buildings.existsByManager_IdAndDeletedAtIsNull(10L)).thenReturn(false);

        assertThatThrownBy(() -> service.getNearbyBuildings(10L, 37.0, 38.0, 127.0, 128.0))
                .isInstanceOfSatisfying(NotFoundException.class, exception -> {
                    assertThat(exception.status).isEqualTo(404);

                    assertThat(exception.code).isEqualTo("BUILDING_NOT_FOUND");
                });

        verifyNoInteractions(locations, rooms);
    }

    @Test
    @DisplayName("범위 내 건물이 없으면 호실 집계 없이 빈 목록을 반환한다")
    void returnsEmptyListWithoutRoomAggregation() {
        registeredManager();
        when(locations.findWithinBounds(37, 38, 127, 128)).thenReturn(List.of());

        assertThat(service.getNearbyBuildings(10L, 37.0, 38.0, 127.0, 128.0).buildings()).isEmpty();

        verifyNoInteractions(rooms);
    }

    private void registeredManager() {
        when(buildings.existsByManager_IdAndDeletedAtIsNull(10L)).thenReturn(true);
    }

}
