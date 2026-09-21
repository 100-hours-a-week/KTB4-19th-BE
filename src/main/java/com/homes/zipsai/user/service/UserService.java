package com.homes.zipsai.user.service;

import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.homes.zipsai.auth.service.TokenService;
import com.homes.zipsai.building.domain.RoomStatus;
import com.homes.zipsai.building.repository.BuildingRepository;
import com.homes.zipsai.building.repository.RoomRepository;
import com.homes.zipsai.global.exception.ConflictException;
import com.homes.zipsai.global.exception.UnauthorizedException;
import com.homes.zipsai.global.exception.ValidationFailedException.Reason;
import com.homes.zipsai.global.security.AuthPrincipal;
import com.homes.zipsai.user.domain.TermsType;
import com.homes.zipsai.user.domain.User;
import com.homes.zipsai.user.domain.UserAgreement;
import com.homes.zipsai.user.domain.UserRole;
import com.homes.zipsai.user.domain.UserStatus;
import com.homes.zipsai.user.dto.EmailAvailabilityResponse;
import com.homes.zipsai.user.dto.UserAgreementResponse;
import com.homes.zipsai.user.dto.UserPatchRequest;
import com.homes.zipsai.user.dto.UserPatchResponse;
import com.homes.zipsai.user.dto.UserProfileResponse;
import com.homes.zipsai.user.repository.TermsRepository;
import com.homes.zipsai.user.repository.UserRepository;
import com.homes.zipsai.user.validator.UserInput;

@Service
public class UserService {
    private final UserRepository users;
    private final TokenService tokens;
    private final TermsRepository terms;
    private final BuildingRepository buildings;
    private final RoomRepository rooms;

    public UserService(UserRepository users, TokenService tokens, TermsRepository terms,
            BuildingRepository buildings, RoomRepository rooms) {
        this.users = users;
        this.tokens = tokens;
        this.terms = terms;
        this.buildings = buildings;
        this.rooms = rooms;
    }

    public User active(Long id) {
        User user = users.findById(id).orElseThrow(UnauthorizedException::new);
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
                !users.existsByEmail(normalizedEmail)
        );
    }

    @Transactional(readOnly = true)
    public UserProfileResponse me(Long id) {
        User user = active(id);
        Long buildingId = buildings.findByManager_IdAndDeletedAtIsNull(id).map(b -> b.getId()).orElse(null);
        Long roomId = rooms.findByResidentIdAndStatus(id, RoomStatus.LIVING).map(r -> r.getId()).orElse(null);
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
    public UserPatchResponse patch(AuthPrincipal principal, UserPatchRequest request) {
        User user = users.findLocked(principal.userId()).orElseThrow(UnauthorizedException::new);
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new UnauthorizedException();
        }
        UserRole updatedRole = null;
        String updatedUserName = null;
        String updatedPhone = null;
        List<UserAgreementResponse> updatedAgreements = null;
        String accessToken = null;
        String tokenType = null;
        if (request.userRole() != null) {
            UserRole role = request.userRole();
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
            accessToken = tokens.access(user, principal.sessionId());
            tokenType = "Bearer";
        }
        if (request.userName() != null) {
            user.changeUserName(UserInput.name(request.userName()));
            updatedUserName = user.getUserName();
        }
        if (request.phone() != null) {
            user.changePhone(UserInput.phone(request.phone()));
            updatedPhone = user.getPhone();
        }
        Map<TermsType, Boolean> updates = UserInput.agreements(request.agreements(), false);
        if (request.agreements() != null) {
            for (var entry : updates.entrySet()) {
                boolean unchanged = agreementViews(user).stream().anyMatch(agreement ->
                        agreement.termsType() == entry.getKey()
                                && agreement.isAgreed() == entry.getValue()
                );
                if (!unchanged) {
                    user.agree(terms.getLatest(entry.getKey()), entry.getValue());
                }
            }
            users.saveAndFlush(user);
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
