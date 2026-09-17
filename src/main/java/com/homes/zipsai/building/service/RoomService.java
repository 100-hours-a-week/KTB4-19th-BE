package com.homes.zipsai.building.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.homes.zipsai.building.domain.Room;
import com.homes.zipsai.building.repository.RoomRepository;
import com.homes.zipsai.global.exception.ForbiddenException;
import com.homes.zipsai.global.exception.NotFoundException;
import com.homes.zipsai.global.exception.ValidationFailedException;
import com.homes.zipsai.global.exception.ValidationFailedException.Reason;

@Service
public class RoomService {
    private final RoomRepository roomRepository;

    public RoomService(RoomRepository roomRepository) {
        this.roomRepository = roomRepository;
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
