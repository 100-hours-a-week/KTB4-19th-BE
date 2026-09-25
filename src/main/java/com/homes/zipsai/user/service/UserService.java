package com.homes.zipsai.user.service;

import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.homes.zipsai.auth.dto.request.AgreementRequest;
import com.homes.zipsai.auth.service.TokenService;
import com.homes.zipsai.building.domain.Building;
import com.homes.zipsai.building.domain.Room;
import com.homes.zipsai.building.domain.RoomStatus;
import com.homes.zipsai.building.repository.BuildingRepository;
import com.homes.zipsai.building.repository.RoomRepository;
import com.homes.zipsai.building.service.ResidentRoomService;
import com.homes.zipsai.global.exception.ConflictException;
import com.homes.zipsai.global.exception.UnauthorizedException;
import com.homes.zipsai.global.exception.ValidationFailedException.Reason;
import com.homes.zipsai.global.security.AuthPrincipal;
import com.homes.zipsai.user.domain.TermsType;
import com.homes.zipsai.user.domain.User;
import com.homes.zipsai.user.domain.UserAgreement;
import com.homes.zipsai.user.domain.UserRole;
import com.homes.zipsai.user.domain.UserStatus;
import com.homes.zipsai.user.dto.response.EmailAvailabilityResponse;
import com.homes.zipsai.user.dto.response.ManagerMyPageResponse;
import com.homes.zipsai.user.dto.response.OnboardingStatusResponse;
import com.homes.zipsai.user.dto.response.ResidentMyPageResponse;
import com.homes.zipsai.user.dto.response.UserAgreementResponse;
import com.homes.zipsai.user.dto.response.UserPatchResponse;
import com.homes.zipsai.user.dto.response.UserProfileResponse;
import com.homes.zipsai.user.repository.TermsRepository;
import com.homes.zipsai.user.repository.UserRepository;
import com.homes.zipsai.user.validator.UserInput;

@Service
public class UserService {
    private final UserRepository userRepository;
    private final TokenService tokenService;
    private final TermsRepository termsRepository;
    private final BuildingRepository buildingRepository;
    private final RoomRepository roomRepository;
    private final ResidentRoomService residentRoomService;

    public UserService(UserRepository userRepository, TokenService tokenService, TermsRepository termsRepository,
            BuildingRepository buildingRepository, RoomRepository roomRepository,
            ResidentRoomService residentRoomService) {
        this.userRepository = userRepository;
        this.tokenService = tokenService;
        this.termsRepository = termsRepository;
        this.buildingRepository = buildingRepository;
        this.roomRepository = roomRepository;
        this.residentRoomService = residentRoomService;
    }

    public User active(Long id) {
        User user = userRepository.findById(id).orElseThrow(UnauthorizedException::new);
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new UnauthorizedException();
        }
        return user;
    }

    @Transactional(readOnly = true)
    public EmailAvailabilityResponse checkEmailAvailability(String email) {
        String normalizedEmail = UserInput.email(email);
        return new EmailAvailabilityResponse(
                normalizedEmail,
                !userRepository.existsByEmail(normalizedEmail)
        );
    }

    @Transactional(readOnly = true)
    public UserProfileResponse me(Long id) {
        User user = active(id);
        Long buildingId = buildingRepository.findByManager_IdAndDeletedAtIsNull(id).map(b -> b.getId()).orElse(null);
        Long roomId = roomRepository.findByResidentIdAndStatus(id, RoomStatus.LIVING).map(r -> r.getId()).orElse(null);
        return new UserProfileResponse(
                user.getId(),
                user.getEmail(),
                user.getRole(),
                buildingId,
                roomId,
                user.getUserName(),
                user.getPhone(),
                agreementViews(user)
        );
    }

    @Transactional(readOnly = true)
    public ManagerMyPageResponse getManagerMyPage(Long id) {
        User user = active(id);
        Building building = buildingRepository.findByManager_IdAndDeletedAtIsNull(id).orElse(null);
        return new ManagerMyPageResponse(
                user.getId(),
                user.getUserName(),
                user.getEmail(),
                user.getPhone(),
                building == null ? null : building.getId(),
                building == null ? null : building.getBuildingName(),
                building == null ? null : building.getRoadAddress()
        );
    }

    @Transactional(readOnly = true)
    public ResidentMyPageResponse getResidentMyPage(Long id) {
        User user = active(id);
        Room room = residentRoomService.getLivingRoom(id);
        User manager = room.getBuilding().getManager();
        return new ResidentMyPageResponse(
                user.getId(),
                user.getUserName(),
                user.getEmail(),
                user.getPhone(),
                room.getId(),
                room.getBuilding().getBuildingName(),
                room.getRoomNo(),
                manager.getUserName(),
                manager.getPhone()
        );
    }

    @Transactional(readOnly = true)
    public OnboardingStatusResponse onboardingStatus(AuthPrincipal principal) {
        User user = active(principal.userId());
        if (user.getRole() == UserRole.NONE) {
            return new OnboardingStatusResponse(UserRole.NONE, null, false, false, "ROLE_SELECTION");
        }
        if (user.getRole() == UserRole.RESIDENT) {
            boolean connected = roomRepository.existsLivingByResidentId(user.getId());
            return new OnboardingStatusResponse(UserRole.RESIDENT, null, false, connected,
                    connected ? "HOME" : "INVITATION_CODE");
        }
        Long buildingId = buildingRepository.findByManager_IdAndDeletedAtIsNull(user.getId())
                .map(b -> b.getId())
                .orElse(null);
        boolean hasRooms = buildingId != null && roomRepository.existsByBuilding_IdAndDeletedAtIsNull(buildingId);
        return new OnboardingStatusResponse(UserRole.MANAGER, buildingId, hasRooms, false,
                buildingId == null ? "BUILDING_REGISTRATION" : hasRooms ? "HOME" : "ROOM_REGISTRATION");
    }

    public static Map<String, Object> profile(User user) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("userId", user.getId()); data.put("userRole", user.getRole());
        data.put("userName", user.getUserName()); data.put("phone", user.getPhone()); return data;
    }

    public static List<UserAgreementResponse> agreementViews(User user) {
        Map<TermsType, UserAgreement> latest = new EnumMap<>(TermsType.class);
        user.getAgreements().stream()
                .sorted(Comparator.comparing(UserAgreement::getId))
                .forEach(agreement -> latest.put(agreement.getTermsType(), agreement));
        return latest.values().stream()
                .map(agreement -> new UserAgreementResponse(
                        agreement.getId(),
                        agreement.getTermsType(),
                        agreement.isAgreed(),
                        agreement.getAgreedAt()
                ))
                .toList();
    }

    @Transactional
    public UserPatchResponse patch(AuthPrincipal principal, UserRole requestedRole, String requestedName,
                                   String requestedPhone, List<AgreementRequest> agreementRequests) {
        User user = userRepository.findLocked(principal.userId()).orElseThrow(UnauthorizedException::new);
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new UnauthorizedException();
        }
        UserRole updatedRole = null;
        String updatedUserName = null;
        String updatedPhone = null;
        List<UserAgreementResponse> updatedAgreements = null;
        String accessToken = null;
        String tokenType = null;
        if (requestedRole != null) {
            UserRole role = requestedRole;
            if (role == UserRole.NONE) {
                throw UserInput.invalid("userRole", Reason.INVALID_USER_ROLE);
            }
            if (user.getRole() != UserRole.NONE && user.getRole() != role) {
                throw new ConflictException(ConflictException.Reason.ROLE_ALREADY_ASSIGNED);
            }
            if (user.getRole() != role) {
                user.selectRole(role);
            }
            updatedRole = role;
            accessToken = tokenService.access(user, principal.sessionId());
            tokenType = "Bearer";
        }
        if (requestedName != null) {
            user.changeUserName(requestedName);
            updatedUserName = user.getUserName();
        }
        if (requestedPhone != null) {
            user.changePhone(UserInput.normalizePhone(requestedPhone));
            updatedPhone = user.getPhone();
        }
        Map<TermsType, Boolean> updates = UserInput.agreements(agreementRequests, false);
        if (agreementRequests != null) {
            for (var entry : updates.entrySet()) {
                boolean unchanged = agreementViews(user).stream().anyMatch(agreement ->
                        agreement.termsType() == entry.getKey()
                                && agreement.isAgreed() == entry.getValue()
                );
                if (!unchanged) {
                    user.agree(termsRepository.getLatest(entry.getKey()), entry.getValue());
                }
            }
            userRepository.saveAndFlush(user);
            updatedAgreements = agreementViews(user).stream()
                    .filter(agreement -> updates.containsKey(agreement.termsType()))
                    .toList();
        }
        return new UserPatchResponse(
                user.getId(),
                updatedRole,
                updatedUserName,
                updatedPhone,
                updatedAgreements,
                accessToken,
                tokenType
        );
    }
}
