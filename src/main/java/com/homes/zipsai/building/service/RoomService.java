package com.homes.zipsai.building.service;

import java.util.HashSet;
import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.homes.zipsai.building.domain.Building;
import com.homes.zipsai.building.domain.Room;
import com.homes.zipsai.building.dto.RoomBulkCreateRequest;
import com.homes.zipsai.building.dto.RoomBulkCreateResponse;
import com.homes.zipsai.building.dto.RoomResponse;
import com.homes.zipsai.building.repository.BuildingRepository;
import com.homes.zipsai.building.repository.RoomRepository;
import com.homes.zipsai.global.exception.ConflictException;
import com.homes.zipsai.global.exception.ForbiddenException;
import com.homes.zipsai.global.exception.MissingFieldException;
import com.homes.zipsai.global.exception.NotFoundException;
import com.homes.zipsai.global.exception.ValidationFailedException;
import com.homes.zipsai.global.exception.ValidationFailedException.Reason;
import com.homes.zipsai.global.security.AuthPrincipal;

@Service
public class RoomService {
    private static final int ROOM_NO_MAX_LENGTH = 5;

    private final BuildingRepository buildings;
    private final RoomRepository rooms;

    public RoomService(BuildingRepository buildings, RoomRepository rooms) {
        this.buildings = buildings;
        this.rooms = rooms;
    }

    /** 선택된 호실을 모두 생성하거나, 하나라도 문제가 있으면 아무것도 저장하지 않습니다. */
    @Transactional
    public RoomBulkCreateResponse create(
            AuthPrincipal principal,
            long buildingId,
            RoomBulkCreateRequest request
    ) {
        if (buildingId < 1) {
            throw new ValidationFailedException("buildingId", Reason.INVALID_ID);
        }
        if (request == null || request.roomNos() == null || request.roomNos().isEmpty()) {
            throw new MissingFieldException("roomNos");
        }

        // 공백을 정리해 저장·중복 검사를 동일한 호실 번호 기준으로 수행합니다.
        List<String> roomNos = request.roomNos().stream()
                .map(roomNo -> roomNo == null ? null : roomNo.trim())
                .toList();
        validateRoomNumbers(roomNos);

        Building building = buildings.findById(buildingId)
                .orElseThrow(() -> new NotFoundException(NotFoundException.Resource.BUILDING));
        // URL의 건물 ID만으로 소유권을 인정하지 않고 JWT principal의 관리자 ID와 대조합니다.
        if (!building.getManager().getId().equals(principal.userId())) {
            throw new ForbiddenException();
        }

        // 요청 내부 중복과 DB에 이미 존재하는 호실을 먼저 찾아 부분 등록을 막습니다.
        if (new HashSet<>(roomNos).size() != roomNos.size()
                || rooms.existsByBuilding_IdAndRoomNoIn(buildingId, roomNos)) {
            throw alreadyExists();
        }

        List<Room> newRooms = roomNos.stream()
                .map(roomNo -> new Room(building, roomNo))
                .toList();
        try {
            // 유니크 제약은 동시 요청 경합에 대한 최종 방어선이며 트랜잭션이 실패 시 전체 롤백됩니다.
            List<RoomResponse> created = rooms.saveAllAndFlush(newRooms).stream()
                    .map(room -> new RoomResponse(room.getId(), room.getRoomNo(), room.getStatus().name()))
                    .toList();
            return new RoomBulkCreateResponse(buildingId, created.size(), created);
        } catch (DataIntegrityViolationException exception) {
            // 사전 중복 조회 직후 다른 요청이 같은 호실을 만들었을 때도 공통 충돌 코드로 바꿉니다.
            throw alreadyExists();
        }
    }

    private void validateRoomNumbers(List<String> roomNos) {
        for (String roomNo : roomNos) {
            if (roomNo == null || roomNo.isBlank()) {
                throw new ValidationFailedException("roomNos", Reason.EMPTY_ROOM_NO);
            }
            if (roomNo.length() > ROOM_NO_MAX_LENGTH) {
                throw new ValidationFailedException("roomNos", Reason.ROOM_NO_TOO_LONG);
            }
        }
    }

    private ConflictException alreadyExists() {
        return new ConflictException(ConflictException.Reason.ROOM_ALREADY_EXISTS);
    }
}
