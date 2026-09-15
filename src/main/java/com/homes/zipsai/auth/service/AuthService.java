package com.homes.zipsai.auth.service;

import java.time.Instant;
import java.util.*;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.homes.zipsai.auth.domain.RefreshSession;
import com.homes.zipsai.auth.repository.RefreshSessionRepository;
import com.homes.zipsai.global.exception.ApiException;
import com.homes.zipsai.global.security.AuthPrincipal;
import com.homes.zipsai.global.security.AuthProperties;
import com.homes.zipsai.user.domain.User;
import com.homes.zipsai.user.domain.UserStatus;
import com.homes.zipsai.user.repository.TermsRepository;
import com.homes.zipsai.user.repository.UserRepository;
import com.homes.zipsai.user.service.UserService;
import com.homes.zipsai.user.validator.UserInput;

import tools.jackson.databind.JsonNode;

@Service
public class AuthService {
    private final UserRepository users;
    private final TermsRepository terms;
    private final RefreshSessionRepository sessions;
    private final PasswordEncoder passwords;
    private final TokenService tokens;
    private final AuthProperties properties;
    private final TransactionTemplate tx;
    private final String dummyHash;
    public AuthService(UserRepository users, RefreshSessionRepository sessions, PasswordEncoder passwords,
                       TokenService tokens, AuthProperties properties, TransactionTemplate tx,
                       TermsRepository terms) {
        this.users = users; this.terms = terms; this.sessions = sessions; this.passwords = passwords; this.tokens = tokens;
        this.properties = properties; this.tx = tx; dummyHash = passwords.encode("dummy-password");
    }
    public Long signup(JsonNode body) {
        UserInput.fields(body, Set.of("email", "password", "passwordConfirm", "userName", "phone", "agreements"));
        String email = UserInput.email(UserInput.text(body, "email", true));
        String password = UserInput.password(UserInput.text(body, "password", true));
        if (!password.equals(UserInput.text(body, "passwordConfirm", true))) throw UserInput.invalid("passwordConfirm", "비밀번호가 일치하지 않습니다.");
        String name = UserInput.name(UserInput.text(body, "userName", false));
        String phone = UserInput.phone(UserInput.text(body, "phone", false));
        var agreements = UserInput.agreements(body, true);
        if (users.existsByEmail(email)) throw duplicate();
        String hash = passwords.encode(password);
        try {
            return tx.execute(status -> {
                User user = new User(email, hash, name, phone);
                agreements.forEach((type, agreed) -> user.agree(terms.getLatest(type), agreed));
                return users.saveAndFlush(user).getId();
            });
        } catch (DataIntegrityViolationException e) {
            if (users.existsByEmail(email)) throw duplicate();
            throw e;
        }
    }
    private ApiException duplicate() {
        return new ApiException(409, "EMAIL_ALREADY_EXISTS", "이미 사용 중인 이메일입니다.", Map.of("field", "email", "reason", "이미 가입된 이메일입니다."));
    }
    public record Tokens(Map<String, Object> data, String refreshToken, Instant expiresAt) {}
    public Tokens login(JsonNode body) {
        UserInput.fields(body, Set.of("email", "password"));
        String email = UserInput.email(UserInput.text(body, "email", true));
        String password = UserInput.password(UserInput.text(body, "password", true));
        User user = users.findByEmail(email).orElse(null);
        boolean matches = passwords.matches(password, user == null ? dummyHash : user.getPassword());
        if (user == null || !matches || user.getStatus() != UserStatus.ACTIVE)
            throw new ApiException(401, "UNAUTHORIZED", "인증이 필요합니다.", Map.of("reason", "이메일 또는 비밀번호가 일치하지 않습니다."));
        return tx.execute(status -> {
            String raw = tokens.refresh();
            RefreshSession session = new RefreshSession(UUID.randomUUID().toString(), user.getId(), TokenService.hash(raw), Instant.now().plus(properties.refreshTtl()));
            sessions.save(session);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("accessToken", tokens.access(user, session.getId())); data.put("tokenType", "Bearer");
            Map<String, Object> view = UserService.profile(user); view.remove("phone"); view.put("email", user.getEmail()); data.put("user", view);
            return new Tokens(data, raw, session.getExpiresAt());
        });
    }
    public Tokens reissue(String raw) {
        if (raw == null || raw.isBlank() || raw.length() > 100) throw ApiException.unauthorized();
        return tx.execute(status -> {
            RefreshSession session = sessions.findLockedByHash(TokenService.hash(raw)).orElseThrow(ApiException::unauthorized);
            if (!session.active()) throw ApiException.unauthorized();
            User user = users.findById(session.getUserId()).orElseThrow(ApiException::unauthorized);
            if (user.getStatus() != UserStatus.ACTIVE) throw ApiException.unauthorized();
            String next = tokens.refresh(); session.rotate(TokenService.hash(next));
            return new Tokens(Map.of("accessToken", tokens.access(user, session.getId()), "tokenType", "Bearer"), next, session.getExpiresAt());
        });
    }
    public void logout(AuthPrincipal principal) {
        tx.executeWithoutResult(status -> {
            RefreshSession session = sessions.findLockedById(principal.sessionId()).orElseThrow(ApiException::unauthorized);
            if (!session.getUserId().equals(principal.userId())) throw ApiException.unauthorized();
            session.revoke();
        });
    }
}
