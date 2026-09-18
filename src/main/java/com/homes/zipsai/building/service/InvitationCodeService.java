package com.homes.zipsai.building.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.homes.zipsai.building.domain.InvitationCode;
import com.homes.zipsai.building.domain.InvitationCodeStatus;
import com.homes.zipsai.building.domain.Room;
import com.homes.zipsai.building.domain.RoomStatus;
import com.homes.zipsai.building.dto.InvitationCodeResponse;
import com.homes.zipsai.building.dto.InvitationCodeValidationResponse;
import com.homes.zipsai.building.repository.InvitationCodeRepository;
import com.homes.zipsai.building.repository.RoomRepository;
import com.homes.zipsai.global.exception.ConflictException;
import com.homes.zipsai.global.exception.ForbiddenException;
import com.homes.zipsai.global.exception.InternalServerException;
import com.homes.zipsai.global.exception.NotFoundException;
import com.homes.zipsai.global.exception.ValidationFailedException;
import com.homes.zipsai.global.exception.ValidationFailedException.Reason;

@Service
public class InvitationCodeService {
    private static final int MAX_GENERATION_ATTEMPTS = 5;
    private static final long EXPIRY_HOURS = 3;

    private final InvitationCodeRepository codeRepository;
    private final RoomRepository roomRepository;
    private final InvitationCodeGenerator generator;
    private final TransactionTemplate transactions;
    private final TransactionTemplate requiresNewTransactions;

    public InvitationCodeService(
            InvitationCodeRepository codeRepository,
            RoomRepository roomRepository,
            InvitationCodeGenerator generator,
            PlatformTransactionManager transactionManager
    ) {
        this.codeRepository = codeRepository;
        this.roomRepository = roomRepository;
        this.generator = generator;
        this.transactions = new TransactionTemplate(transactionManager);
        this.requiresNewTransactions = new TransactionTemplate(transactionManager);
        this.requiresNewTransactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public InvitationCodeResponse issueOrReissue(Long managerId, Long roomId) {
        validateId("roomId", roomId);
        for (int attempt = 0; attempt < MAX_GENERATION_ATTEMPTS; attempt++) {
            try {
                return transactions.execute(status -> {
                    Room room = roomRepository.findByIdWithBuildingAndManager(roomId)
                            .orElseThrow(() -> new NotFoundException(NotFoundException.Resource.ROOM));
                    validateRoomOwnership(room, managerId);
                    room.invite();
                    return issueOnce(room);
                });
            } catch (DataIntegrityViolationException exception) {
                continue;
            }
        }
        throw new InternalServerException();
    }

    public InvitationCodeValidationResponse validateInvitationCode(String rawCode) {
        InvitationCode invitationCode = getValidInvitation(
                rawCode,
                ConflictException.Reason.INVITATION_CODE_ALREADY_USED);

        Room room = invitationCode.getRoom();
        return new InvitationCodeValidationResponse(
                room.getBuilding().getBuildingName(),
                room.getRoomNo()
        );
    }

    public void cancelInvitation(Long managerId, Long roomId, Long codeId) {
        validateId("roomId", roomId);
        validateId("codeId", codeId);
        transactions.executeWithoutResult(status -> {
            Room room = roomRepository.findByIdWithBuildingAndManager(roomId)
                    .orElseThrow(() -> new NotFoundException(NotFoundException.Resource.ROOM));
            validateRoomOwnership(room, managerId);

            InvitationCode invitationCode = codeRepository.findByIdAndRoomId(codeId, roomId)
                    .orElseThrow(() -> new NotFoundException(NotFoundException.Resource.INVITATION_CODE_BY_ID));
            if (invitationCode.getStatus() != InvitationCodeStatus.ACTIVE) {
                throw new ConflictException(ConflictException.Reason.INVITATION_CANCEL_NOT_ALLOWED);
            }

            invitationCode.expire();
            room.cancelInvitation();
        });
    }

    InvitationCode getValidInvitation(String rawCode, ConflictException.Reason usedReason) {
        String code = normalizeCode(rawCode);
        InvitationCode invitationCode = codeRepository.findByCodeWithRoomAndBuilding(code)
                .orElseThrow(() -> new NotFoundException(NotFoundException.Resource.INVITATION_CODE_BY_CODE));

        if (invitationCode.getStatus() == InvitationCodeStatus.USED) {
            throw new ConflictException(usedReason);
        }
        if (invitationCode.getStatus() == InvitationCodeStatus.EXPIRED) {
            throw new ConflictException(ConflictException.Reason.INVITATION_CODE_EXPIRED);
        }
        LocalDateTime now = LocalDateTime.now();
        if (invitationCode.isExpired(now)) {
            expireIfExpired(invitationCode.getId(), now);
            throw new ConflictException(ConflictException.Reason.INVITATION_CODE_EXPIRED);
        }
        return invitationCode;
    }

    private void expireIfExpired(Long codeId, LocalDateTime now) {
        requiresNewTransactions.executeWithoutResult(status -> codeRepository.findById(codeId)
                .ifPresent(code -> code.expireIfExpired(now)));
    }

    private InvitationCodeResponse issueOnce(Room room) {
        String value = generator.generate();
        List<InvitationCode> activeCodes = codeRepository.findActiveByRoomId(room.getId());
        boolean reissued = !activeCodes.isEmpty();

        LocalDateTime issuedAt = LocalDateTime.now();
        InvitationCode invitationCode = new InvitationCode(room, value, issuedAt.plusHours(EXPIRY_HOURS));
        codeRepository.save(invitationCode);
        activeCodes.forEach(InvitationCode::expire);

        return createResponse(room, invitationCode, reissued);
    }

    private void validateRoomOwnership(Room room, Long managerId) {
        if (!room.getBuilding().getManager().getId().equals(managerId)) {
            throw new ForbiddenException();
        }
    }

    private InvitationCodeResponse createResponse(
            Room room,
            InvitationCode invitationCode,
            boolean reissued
    ) {
        RoomStatus roomStatus = room.getStatus();
        return new InvitationCodeResponse(
                room.getId(),
                room.getRoomNo(),
                roomStatus,
                roomStatus.getLabel(),
                invitationCode.getId(),
                invitationCode.getCode(),
                invitationCode.getCreatedAt(),
                invitationCode.getExpiresAt(),
                reissued
        );
    }

    private void validateId(String field, Long id) {
        if (id == null || id <= 0) {
            throw new ValidationFailedException(field, Reason.INVALID_ID);
        }
    }

    private String normalizeCode(String rawCode) {
        String code = rawCode == null ? "" : rawCode.trim().toUpperCase(Locale.ROOT);
        if (!code.matches("[A-HJ-NP-Z2-9]{6}")) {
            throw new ValidationFailedException("code", Reason.INVALID_INVITATION_CODE);
        }
        return code;
    }
}
