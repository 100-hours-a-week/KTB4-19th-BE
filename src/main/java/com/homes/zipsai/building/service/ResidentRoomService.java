package com.homes.zipsai.building.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.homes.zipsai.building.domain.Room;
import com.homes.zipsai.building.domain.RoomStatus;
import com.homes.zipsai.building.repository.RoomRepository;
import com.homes.zipsai.global.exception.ForbiddenException;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ResidentRoomService {

    private final RoomRepository roomRepository;

    @Transactional(readOnly = true)
    public Room getLivingRoom(Long residentId) {
        return roomRepository.findByResidentIdAndStatus(residentId, RoomStatus.LIVING)
            .orElseThrow(ForbiddenException::new);
    }
}
