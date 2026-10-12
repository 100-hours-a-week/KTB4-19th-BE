package com.homes.zipsai.building.service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.homes.zipsai.building.dto.BuildingRoomCounts;
import com.homes.zipsai.building.dto.NearbyBuilding;
import com.homes.zipsai.building.dto.response.ManagerNearbyBuildingListResponse;
import com.homes.zipsai.building.dto.response.ManagerNearbyBuildingResponse;
import com.homes.zipsai.building.dto.response.NearbyRoomSummaryResponse;
import com.homes.zipsai.building.repository.BuildingLocationRepository;
import com.homes.zipsai.building.repository.BuildingRepository;
import com.homes.zipsai.building.repository.RoomRepository;
import com.homes.zipsai.global.exception.InvalidQueryParameterException;
import com.homes.zipsai.global.exception.NotFoundException;

@Service
public class NearbyBuildingService {

    private final BuildingRepository buildingRepository;
    private final BuildingLocationRepository buildingLocationRepository;
    private final RoomRepository roomRepository;

    public NearbyBuildingService(BuildingRepository buildingRepository,
            BuildingLocationRepository buildingLocationRepository, RoomRepository roomRepository) {
        this.buildingRepository = buildingRepository;
        this.buildingLocationRepository = buildingLocationRepository;
        this.roomRepository = roomRepository;
    }

    @Transactional(readOnly = true)
    public ManagerNearbyBuildingListResponse getNearbyBuildings(Long managerId, Double minLatitude, Double maxLatitude,
                                                                Double minLongitude, Double maxLongitude) {
        if (minLatitude >= maxLatitude) {
            throw new InvalidQueryParameterException(
                    "maxLatitude", InvalidQueryParameterException.Reason.INVALID_BOUNDS);
        }
        if (minLongitude >= maxLongitude) {
            throw new InvalidQueryParameterException(
                    "maxLongitude", InvalidQueryParameterException.Reason.INVALID_BOUNDS);
        }
        if (!buildingRepository.existsByManager_IdAndDeletedAtIsNull(managerId)) {
            throw new NotFoundException(NotFoundException.Resource.BUILDING);
        }
        List<NearbyBuilding> buildings = buildingLocationRepository.findWithinBounds(
                minLatitude, maxLatitude, minLongitude, maxLongitude);
        if (buildings.isEmpty()) {
            return new ManagerNearbyBuildingListResponse(List.of());
        }
        List<Long> buildingIds = buildings.stream().map(NearbyBuilding::buildingId).toList();
        Map<Long, BuildingRoomCounts> countsByBuilding = roomRepository.summarizeByBuildingIds(buildingIds).stream()
                .collect(Collectors.toMap(BuildingRoomCounts::buildingId, counts -> counts));
        return new ManagerNearbyBuildingListResponse(buildings.stream().map(building -> {
            BuildingRoomCounts counts = countsByBuilding.get(building.buildingId());
            NearbyRoomSummaryResponse summary = counts == null
                    ? new NearbyRoomSummaryResponse(0, 0, 0)
                    : new NearbyRoomSummaryResponse(counts.totalCount(), counts.livingCount(), counts.emptyCount());
            return new ManagerNearbyBuildingResponse(building.buildingId(), building.buildingName(),
                    building.latitude(), building.longitude(), summary);
        }).toList());
    }
}
