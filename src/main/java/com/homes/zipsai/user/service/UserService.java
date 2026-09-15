package com.homes.zipsai.user.service;

import java.util.*;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.homes.zipsai.auth.service.TokenService;
import com.homes.zipsai.global.exception.ApiException;
import com.homes.zipsai.global.security.AuthPrincipal;
import com.homes.zipsai.user.domain.TermsType;
import com.homes.zipsai.user.domain.User;
import com.homes.zipsai.user.domain.UserAgreement;
import com.homes.zipsai.user.domain.UserRole;
import com.homes.zipsai.user.domain.UserStatus;
import com.homes.zipsai.user.repository.TermsRepository;
import com.homes.zipsai.user.repository.UserRepository;
import com.homes.zipsai.user.validator.UserInput;

import tools.jackson.databind.JsonNode;

@Service
public class UserService {
    private final UserRepository users;
    private final TokenService tokens;
    private final TermsRepository terms;
    public UserService(UserRepository users, TokenService tokens, TermsRepository terms) {
        this.users = users; this.tokens = tokens; this.terms = terms;
    }
    public User active(Long id) {
        User user = users.findById(id).orElseThrow(ApiException::unauthorized);
        if (user.getStatus() != UserStatus.ACTIVE) throw ApiException.unauthorized();
        return user;
    }
    @Transactional(readOnly = true)
    public Map<String, Object> me(Long id) {
        User user = active(id);
        Map<String, Object> data = profile(user);
        data.put("email", user.getEmail()); data.put("agreements", agreementViews(user)); return data;
    }
    public static Map<String, Object> profile(User user) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("userId", user.getId()); data.put("userRole", user.getRole());
        data.put("userName", user.getUserName()); data.put("phone", user.getPhone()); return data;
    }
    public static List<Map<String, Object>> agreementViews(User user) {
        Map<TermsType, UserAgreement> latest = new EnumMap<>(TermsType.class);
        user.getAgreements().stream().sorted(Comparator.comparing(UserAgreement::getId)).forEach(a -> latest.put(a.getTermsType(), a));
        return latest.values().stream().map(a -> {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("agreementId", a.getId()); view.put("termsType", a.getTermsType());
            view.put("isAgreed", a.isAgreed()); view.put("agreedAt", a.getAgreedAt()); return view;
        }).toList();
    }
    @Transactional
    public Map<String, Object> patch(AuthPrincipal principal, JsonNode body) {
        UserInput.fields(body, Set.of("userRole", "userName", "phone", "agreements"));
        User user = users.findLocked(principal.userId()).orElseThrow(ApiException::unauthorized);
        if (user.getStatus() != UserStatus.ACTIVE) throw ApiException.unauthorized();
        Map<String, Object> result = new LinkedHashMap<>(); result.put("userId", user.getId());
        if (body.has("userRole")) {
            UserRole role;
            try { role = UserRole.valueOf(UserInput.text(body, "userRole", true)); }
            catch (IllegalArgumentException e) { throw UserInput.invalid("userRole", "허용되지 않은 역할입니다."); }
            if (role == UserRole.NONE) throw UserInput.invalid("userRole", "허용되지 않은 역할입니다.");
            if (user.getRole() != UserRole.NONE && user.getRole() != role)
                throw new ApiException(409, "ROLE_ALREADY_ASSIGNED", "이미 역할이 설정된 계정입니다.", Map.of("field", "userRole", "reason", "역할은 최초 1회만 설정할 수 있습니다."));
            if (user.getRole() != role) user.selectRole(role);
            result.put("userRole", role);
            result.put("accessToken", tokens.access(user, principal.sessionId())); result.put("tokenType", "Bearer");
        }
        if (body.has("userName")) { user.changeUserName(UserInput.name(UserInput.text(body, "userName", false))); result.put("userName", user.getUserName()); }
        if (body.has("phone")) { user.changePhone(UserInput.phone(UserInput.text(body, "phone", false))); result.put("phone", user.getPhone()); }
        Map<TermsType, Boolean> updates = UserInput.agreements(body, false);
        if (body.has("agreements")) {
            for (var entry : updates.entrySet()) {
                boolean unchanged = agreementViews(user).stream().anyMatch(a -> a.get("termsType") == entry.getKey() && a.get("isAgreed").equals(entry.getValue()));
                if (!unchanged) user.agree(terms.getLatest(entry.getKey()), entry.getValue());
            }
            users.saveAndFlush(user);
            result.put("agreements", agreementViews(user).stream().filter(a -> updates.containsKey(a.get("termsType"))).toList());
        }
        return result;
    }
}
