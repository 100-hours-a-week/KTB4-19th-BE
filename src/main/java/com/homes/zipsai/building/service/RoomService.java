package com.homes.zipsai.building.service;

import java.util.HashSet;
import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.homes.zipsai.building.domain.Building;
import com.homes.zipsai.building.domain.Room;
import com.homes.zipsai.building.dto.RoomBulkCreateResponse;
import com.homes.zipsai.building.dto.RoomResponse;
import com.homes.zipsai.building.repository.BuildingRepository;
import com.homes.zipsai.building.repository.RoomRepository;
import com.homes.zipsai.global.exception.ConflictException;
import com.homes.zipsai.global.exception.ForbiddenException;
import com.homes.zipsai.global.exception.NotFoundException;
import com.homes.zipsai.global.exception.ValidationFailedException;
import com.homes.zipsai.global.exception.ValidationFailedException.Reason;

@Service
public class RoomService {
    private static final int ROOM_NO_MAX_LENGTH = 5;

    private final BuildingRepository buildingRepository;
    private final RoomRepository roomRepository;

    public RoomService(BuildingRepository buildingRepository, RoomRepository roomRepository) {
        this.buildingRepository = buildingRepository;
        this.roomRepository = roomRepository;
    }

    @Transactional
    public RoomBulkCreateResponse create(Long userId, List<String> requestedRoomNos) {
        List<String> roomNos = requestedRoomNos.stream()
                .map(String::trim)
                .toList();
        validateRoomNumbers(roomNos);

        Building building = buildingRepository.findByManager_IdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new NotFoundException(NotFoundException.Resource.BUILDING));

        if (new HashSet<>(roomNos).size() != roomNos.size()
                || roomRepository.existsByBuilding_IdAndRoomNoIn(building.getId(), roomNos)) {
            throw alreadyExists();
        }

        List<Room> newRooms = roomNos.stream()
                .map(roomNo -> new Room(building, roomNo))
                .toList();
        try {
            List<RoomResponse> created = roomRepository.saveAllAndFlush(newRooms).stream()
                    .map(room -> new RoomResponse(room.getId(), room.getRoomNo(), room.getStatus().name()))
                    .toList();
            return new RoomBulkCreateResponse(building.getId(), created.size(), created);
        } catch (DataIntegrityViolationException exception) {
            throw alreadyExists();
        }
    }

    private void validateRoomNumbers(List<String> roomNos) {
        for (String roomNo : roomNos) {
            if (roomNo.length() > ROOM_NO_MAX_LENGTH) {
                throw new ValidationFailedException("roomNos", Reason.ROOM_NO_TOO_LONG);
            }
        }
    }

    private ConflictException alreadyExists() {
        return new ConflictException(ConflictException.Reason.ROOM_ALREADY_EXISTS);
    }

    @Transactional
    public void moveOutResident(Long managerId, Long roomId) {
        validateId(roomId);
        Room room = roomRepository.findByIdWithBuildingAndManager(roomId)
                .orElseThrow(() -> new NotFoundException(NotFoundException.Resource.ROOM));
        if (!room.getBuilding().getManager().getId().equals(managerId)) {
            throw new ForbiddenException();
        }
        room.moveOutResident();
    }

    private void validateId(Long roomId) {
        if (roomId == null || roomId <= 0) {
            throw new ValidationFailedException("roomId", Reason.INVALID_ID);
        }
    }
}
