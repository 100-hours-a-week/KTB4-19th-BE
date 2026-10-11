package com.homes.zipsai.building.service;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Optional;

import org.locationtech.jts.geom.Point;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.homes.zipsai.building.client.KakaoAddressClient;
import com.homes.zipsai.building.domain.Building;
import com.homes.zipsai.building.domain.BuildingLocation;
import com.homes.zipsai.building.domain.Room;
import com.homes.zipsai.building.domain.RoomStatus;
import com.homes.zipsai.building.dto.BuildingResponse;
import com.homes.zipsai.building.dto.response.ManagerBuildingDetailResponse;
import com.homes.zipsai.building.dto.response.ManagerComplaintSummaryResponse;
import com.homes.zipsai.building.dto.response.ManagerRoomItemResponse;
import com.homes.zipsai.building.dto.response.ManagerRoomListResponse;
import com.homes.zipsai.building.dto.response.ManagerRoomSummaryResponse;
import com.homes.zipsai.building.repository.BuildingLocationRepository;
import com.homes.zipsai.building.repository.BuildingRepository;
import com.homes.zipsai.building.repository.ComplaintRepository;
import com.homes.zipsai.building.repository.RoomRepository;
import com.homes.zipsai.global.exception.ConflictException;
import com.homes.zipsai.global.exception.NotFoundException;
import com.homes.zipsai.global.exception.UnauthorizedException;
import com.homes.zipsai.user.domain.User;
import com.homes.zipsai.user.repository.UserRepository;

@Service
public class BuildingService {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final BuildingRepository buildingRepository;
    private final ComplaintRepository complaintRepository;
    private final RoomRepository roomRepository;
    private final UserRepository userRepository;
    private final BuildingLocationRepository buildingLocationRepository;
    private final KakaoAddressClient kakaoAddressClient;
    private final TransactionTemplate transactionTemplate;

    public BuildingService(
            BuildingRepository buildingRepository,
            ComplaintRepository complaintRepository,
            RoomRepository roomRepository,
            UserRepository userRepository,
            BuildingLocationRepository buildingLocationRepository,
            KakaoAddressClient kakaoAddressClient,
            TransactionTemplate transactionTemplate
    ) {
        this.buildingRepository = buildingRepository;
        this.complaintRepository = complaintRepository;
        this.roomRepository = roomRepository;
        this.userRepository = userRepository;
        this.buildingLocationRepository = buildingLocationRepository;
        this.kakaoAddressClient = kakaoAddressClient;
        this.transactionTemplate = transactionTemplate;
    }

    public BuildingResponse register(Long userId, String buildingName, String roadAddress) {
        User manager = userRepository.findById(userId).orElseThrow(UnauthorizedException::new);
        if (buildingRepository.existsByManager_Id(manager.getId())) {
            throw new ConflictException(ConflictException.Reason.BUILDING_ALREADY_EXISTS);
        }
        Optional<Point> coordinates = kakaoAddressClient.findCoordinates(roadAddress);
        try {
            return transactionTemplate.execute(transaction -> {
                if (buildingRepository.existsByManager_Id(userId)) {
                    throw new ConflictException(ConflictException.Reason.BUILDING_ALREADY_EXISTS);
                }
                Building saved = buildingRepository.saveAndFlush(new Building(manager, roadAddress, buildingName));
                coordinates.ifPresent(point ->
                    buildingLocationRepository.saveAndFlush(new BuildingLocation(saved, point)));
                return new BuildingResponse(saved.getId(), saved.getBuildingName(), saved.getRoadAddress());
            });
        } catch (DataIntegrityViolationException exception) {
            if (buildingRepository.existsByManager_Id(userId)) {
                throw new ConflictException(ConflictException.Reason.BUILDING_ALREADY_EXISTS);
            }
            throw exception;
        }
    }

    @Transactional(readOnly = true)
    public ManagerBuildingDetailResponse getBuilding(Long managerId) {
        Building building = ownedBuilding(managerId);
        return new ManagerBuildingDetailResponse(
                building.getId(),
                building.getBuildingName(),
                building.getRoadAddress(),
                roomRepository.countByBuilding_IdAndDeletedAtIsNull(building.getId()),
                building.getUpdatedAt());
    }

    @Transactional(readOnly = true)
    public ManagerRoomSummaryResponse getRoomSummary(Long managerId) {
        Building building = ownedBuilding(managerId);
        long livingCount = roomRepository.countByBuilding_IdAndStatusAndDeletedAtIsNull(
                building.getId(), RoomStatus.LIVING);
        long invitedCount = roomRepository.countByBuilding_IdAndStatusAndDeletedAtIsNull(
                building.getId(), RoomStatus.INVITED);
        long emptyCount = roomRepository.countByBuilding_IdAndStatusAndDeletedAtIsNull(
                building.getId(), RoomStatus.EMPTY);
        return new ManagerRoomSummaryResponse(
                livingCount,
                invitedCount,
                emptyCount,
                livingCount + invitedCount + emptyCount);
    }

    @Transactional(readOnly = true)
    public ManagerRoomListResponse getRooms(Long managerId) {
        Building building = ownedBuilding(managerId);
        List<ManagerRoomItemResponse> roomItems = activeRooms(building).stream()
                .map(this::roomItem)
                .toList();
        return new ManagerRoomListResponse(
                building.getId(),
                building.getBuildingName(),
                roomItems.size(),
                roomItems);
    }

    @Transactional(readOnly = true)
    public ManagerComplaintSummaryResponse getComplaintSummary(Long managerId) {
        Building building = ownedBuilding(managerId);
        LocalDateTime now = LocalDateTime.now(SEOUL);
        LocalDateTime weekStart = now.toLocalDate()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .atStartOfDay();
        return complaintRepository.findManagerComplaintSummary(building.getId(), weekStart, now);
    }

    private Building ownedBuilding(Long managerId) {
        return buildingRepository.findByManager_IdAndDeletedAtIsNull(managerId)
                .orElseThrow(() -> new NotFoundException(NotFoundException.Resource.BUILDING));
    }

    private List<Room> activeRooms(Building building) {
        return roomRepository.findAllByBuilding_IdAndDeletedAtIsNull(building.getId());
    }

    private ManagerRoomItemResponse roomItem(Room room) {
        return new ManagerRoomItemResponse(
                room.getId(),
                room.getRoomNo(),
                room.getStatus().name(),
                roomStatusLabel(room.getStatus()),
                room.getResident() == null ? null : room.getResident().getUserName());
    }

    private String roomStatusLabel(RoomStatus status) {
        return switch (status) {
            case LIVING -> "입주";
            case INVITED -> "초대됨";
            case EMPTY -> "공실";
        };
    }

}
