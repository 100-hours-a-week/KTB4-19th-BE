package com.homes.zipsai.building.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.homes.zipsai.building.domain.InvitationCode;
import com.homes.zipsai.building.domain.Room;
import com.homes.zipsai.building.dto.RoomConnectionResponse;
import com.homes.zipsai.building.repository.RoomRepository;
import com.homes.zipsai.global.exception.ConflictException;
import com.homes.zipsai.global.exception.UnauthorizedException;
import com.homes.zipsai.user.domain.User;
import com.homes.zipsai.user.repository.UserRepository;

@Service
public class RoomConnectionService {
    private final InvitationCodeService invitationCodeService;
    private final RoomRepository roomRepository;
    private final UserRepository userRepository;

    public RoomConnectionService(
            InvitationCodeService invitationCodeService,
            RoomRepository roomRepository,
            UserRepository userRepository
    ) {
        this.invitationCodeService = invitationCodeService;
        this.roomRepository = roomRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public RoomConnectionResponse connectRoom(Long residentId, String rawCode) {
        InvitationCode invitationCode = invitationCodeService.getValidInvitation(
                rawCode,
                ConflictException.Reason.ROOM_CONNECTION_CONFLICT);
        User resident = userRepository.findById(residentId)
                .orElseThrow(UnauthorizedException::new);
        if (roomRepository.existsLivingByResidentId(residentId)) {
            throw new ConflictException(ConflictException.Reason.ROOM_CONNECTION_CONFLICT);
        }

        Room room = invitationCode.getRoom();
        room.connect(resident);
        invitationCode.use();
        return new RoomConnectionResponse(room.getBuilding().getBuildingName(), room.getRoomNo());
    }
}
