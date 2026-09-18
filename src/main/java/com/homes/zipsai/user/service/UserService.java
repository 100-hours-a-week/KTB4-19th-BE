package com.homes.zipsai.user.service;

import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.homes.zipsai.auth.service.TokenService;
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

    public UserService(UserRepository users, TokenService tokens, TermsRepository terms) {
        this.users = users;
        this.tokens = tokens;
        this.terms = terms;
    }

    public User active(Long id) {
        User user = users.findById(id).orElseThrow(UnauthorizedException::new);
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new UnauthorizedException();
        }
        return user;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> me(Long id) {
        User user = active(id);
        Map<String, Object> data = profile(user);
        data.put("email", user.getEmail());
        data.put("agreements", agreementViews(user));
        return data;
    }

    public static Map<String, Object> profile(User user) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("userId", user.getId());
        data.put("userRole", user.getRole());
        data.put("userName", user.getUserName());
        data.put("phone", user.getPhone());
        return data;
    }

    public static List<Map<String, Object>> agreementViews(User user) {
        Map<TermsType, UserAgreement> latest = new EnumMap<>(TermsType.class);
        user.getAgreements().stream()
                .sorted(Comparator.comparing(UserAgreement::getId))
                .forEach(agreement -> latest.put(agreement.getTermsType(), agreement));

        return latest.values().stream()
                .map(agreement -> {
                    Map<String, Object> view = new LinkedHashMap<>();
                    view.put("agreementId", agreement.getId());
                    view.put("termsType", agreement.getTermsType());
                    view.put("isAgreed", agreement.isAgreed());
                    view.put("agreedAt", agreement.getAgreedAt());
                    return view;
                })
                .toList();
    }

    @Transactional
    public UserPatchResponse patch(AuthPrincipal principal, UserPatchRequest request) {
        User user = users.findLocked(principal.userId()).orElseThrow(UnauthorizedException::new);
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new UnauthorizedException();
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("userId", user.getId());
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
            result.put("userRole", role);
            result.put("accessToken", tokens.access(user));
            result.put("tokenType", "Bearer");
        }
        if (request.userName() != null) {
            user.changeUserName(UserInput.name(request.userName()));
            result.put("userName", user.getUserName());
        }
        if (request.phone() != null) {
            user.changePhone(UserInput.phone(request.phone()));
            result.put("phone", user.getPhone());
        }

        Map<TermsType, Boolean> updates = UserInput.agreements(request.agreements(), false);
        if (request.agreements() != null) {
            for (var entry : updates.entrySet()) {
                boolean unchanged = agreementViews(user).stream()
                        .anyMatch(agreement -> agreement.get("termsType") == entry.getKey()
                                && agreement.get("isAgreed").equals(entry.getValue()));
                if (!unchanged) {
                    user.agree(terms.getLatest(entry.getKey()), entry.getValue());
                }
            }
            users.saveAndFlush(user);
            result.put("agreements", agreementViews(user).stream()
                    .filter(agreement -> updates.containsKey(agreement.get("termsType")))
                    .toList());
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
