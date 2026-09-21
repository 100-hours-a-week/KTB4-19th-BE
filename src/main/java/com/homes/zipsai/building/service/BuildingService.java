package com.homes.zipsai.building.service;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.homes.zipsai.building.domain.Building;
import com.homes.zipsai.building.domain.Room;
import com.homes.zipsai.building.domain.RoomStatus;
import com.homes.zipsai.building.dto.BuildingRegistrationRequest;
import com.homes.zipsai.building.dto.BuildingResponse;
import com.homes.zipsai.building.dto.response.ManagerBuildingDetailResponse;
import com.homes.zipsai.building.dto.response.ManagerComplaintSummaryResponse;
import com.homes.zipsai.building.dto.response.ManagerRoomItemResponse;
import com.homes.zipsai.building.dto.response.ManagerRoomListResponse;
import com.homes.zipsai.building.dto.response.ManagerRoomSummaryResponse;
import com.homes.zipsai.building.repository.BuildingRepository;
import com.homes.zipsai.building.repository.ComplaintRepository;
import com.homes.zipsai.building.repository.RoomRepository;
import com.homes.zipsai.global.exception.ConflictException;
import com.homes.zipsai.global.exception.MissingFieldException;
import com.homes.zipsai.global.exception.NotFoundException;
import com.homes.zipsai.global.exception.UnauthorizedException;
import com.homes.zipsai.global.exception.ValidationFailedException;
import com.homes.zipsai.global.exception.ValidationFailedException.Reason;
import com.homes.zipsai.global.security.AuthPrincipal;
import com.homes.zipsai.user.domain.User;
import com.homes.zipsai.user.repository.UserRepository;

@Service
public class BuildingService {
    // DB 컬럼 길이와 동일한 제한을 두어 저장 단계에서 길이 오류가 나지 않게 합니다.
    private static final int ROAD_ADDRESS_MAX_LENGTH = 200;
    private static final int BUILDING_NAME_MAX_LENGTH = 20;
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final BuildingRepository buildings;
    private final ComplaintRepository complaints;
    private final RoomRepository rooms;
    private final UserRepository users;

    public BuildingService(
            BuildingRepository buildings,
            ComplaintRepository complaints,
            RoomRepository rooms,
            UserRepository users
    ) {
        this.buildings = buildings;
        this.complaints = complaints;
        this.rooms = rooms;
        this.users = users;
    }

    /** 입력을 검증하고 로그인한 매니저에게 건물을 한 건 등록합니다. */
    @Transactional
    public BuildingResponse register(AuthPrincipal principal, BuildingRegistrationRequest request) {
        // 주소는 등록에 반드시 필요하므로 누락되거나 공백뿐이면 공통 필수값 오류를 반환합니다.
        if (request == null || request.roadAddress() == null || request.roadAddress().isBlank()) {
            throw new MissingFieldException("roadAddress");
        }

        // 앞뒤 공백을 정리한 뒤 DB 컬럼 크기를 기준으로 길이를 검사합니다.
        String roadAddress = request.roadAddress().trim();
        if (roadAddress.length() > ROAD_ADDRESS_MAX_LENGTH) {
            throw new ValidationFailedException("roadAddress", Reason.ROAD_ADDRESS_TOO_LONG);
        }

        // 건물명은 선택 입력입니다. 공백만 입력한 경우에도 미입력(null)으로 저장합니다.
        String buildingName = request.buildingName();
        if (buildingName != null) {
            buildingName = buildingName.trim();
            if (buildingName.isEmpty()) {
                buildingName = null;
            } else if (buildingName.length() > BUILDING_NAME_MAX_LENGTH) {
                throw new ValidationFailedException("buildingName", Reason.BUILDING_NAME_TOO_LONG);
            }
        }

        // 요청 본문에 포함된 사용자 ID를 신뢰하지 않고 인증 principal의 ID로 소유자를 결정합니다.
        User manager = users.findById(principal.userId()).orElseThrow(UnauthorizedException::new);
        if (buildings.existsByManager_Id(manager.getId())) {
            throw alreadyRegistered();
        }

        Building building = new Building(manager, roadAddress, buildingName);
        try {
            // flush 시점에 DB 제약 위반을 확인해 중복 등록 경합도 아래에서 처리할 수 있습니다.
            Building saved = buildings.saveAndFlush(building);
            return new BuildingResponse(saved.getId(), saved.getBuildingName(), saved.getRoadAddress());
        } catch (DataIntegrityViolationException exception) {
            // 동시에 두 요청이 들어온 경우 사전 exists 검사 뒤에도 유니크 제약에 걸릴 수 있습니다.
            if (buildings.existsByManager_Id(manager.getId())) {
                throw alreadyRegistered();
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
                rooms.countByBuilding_IdAndDeletedAtIsNull(building.getId()),
                building.getUpdatedAt());
    }

    @Transactional(readOnly = true)
    public ManagerRoomSummaryResponse getRoomSummary(Long managerId) {
        Building building = ownedBuilding(managerId);
        long livingCount = rooms.countByBuilding_IdAndStatusAndDeletedAtIsNull(building.getId(), RoomStatus.LIVING);
        long invitedCount = rooms.countByBuilding_IdAndStatusAndDeletedAtIsNull(building.getId(), RoomStatus.INVITED);
        long emptyCount = rooms.countByBuilding_IdAndStatusAndDeletedAtIsNull(building.getId(), RoomStatus.EMPTY);
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
        return complaints.findManagerComplaintSummary(building.getId(), weekStart, now);
    }

    private Building ownedBuilding(Long managerId) {
        return buildings.findByManager_IdAndDeletedAtIsNull(managerId)
                .orElseThrow(() -> new NotFoundException(NotFoundException.Resource.BUILDING));
    }

    private List<Room> activeRooms(Building building) {
        return rooms.findAllByBuilding_IdAndDeletedAtIsNull(building.getId());
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

    // 같은 매니저의 두 번째 등록은 공통 충돌 코드로 응답합니다.
    private ConflictException alreadyRegistered() {
        return new ConflictException(ConflictException.Reason.BUILDING_ALREADY_EXISTS);
    }
}
