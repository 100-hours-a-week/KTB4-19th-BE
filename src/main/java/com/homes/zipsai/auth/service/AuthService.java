package com.homes.zipsai.auth.service;

import java.time.Instant;
import java.util.*;

import com.homes.zipsai.auth.dto.LoginRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.homes.zipsai.auth.domain.RefreshSession;
import com.homes.zipsai.auth.repository.RefreshSessionRepository;
import com.homes.zipsai.global.exception.ConflictException;
import com.homes.zipsai.global.exception.InvalidCredentialsException;
import com.homes.zipsai.global.exception.UnauthorizedException;
import com.homes.zipsai.global.exception.ValidationFailedException.Reason;
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
        if (!password.equals(UserInput.text(body, "passwordConfirm", true))) {
            throw UserInput.invalid(
                    "passwordConfirm", Reason.PASSWORD_CONFIRMATION_MISMATCH);
        }
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
    private ConflictException duplicate() {
        return new ConflictException(ConflictException.Reason.EMAIL_ALREADY_EXISTS);
    }
    public record Tokens(Map<String, Object> data, String refreshToken, Instant expiresAt) {}
    public Tokens login(LoginRequest loginRequest) {
        String email = UserInput.email(loginRequest.getEmail());
        String password = UserInput.password(loginRequest.getPassword());
        User user = users.findByEmail(email).orElse(null);
        boolean matches = passwords.matches(password, user == null ? dummyHash : user.getPassword());
        if (user == null || !matches || user.getStatus() != UserStatus.ACTIVE)
            throw new InvalidCredentialsException();
        return tx.execute(status -> {
            String raw = tokens.refresh();
            RefreshSession session = new RefreshSession(UUID.randomUUID().toString(), user.getId(), TokenService.hash(raw), Instant.now().plus(properties.refreshTtl()));
            sessions.save(session);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("accessToken", tokens.access(user, session.getId()));
            data.put("tokenType", "Bearer");
            Map<String, Object> view = UserService.profile(user);
            view.remove("phone");
            view.put("email", user.getEmail());
            data.put("user", view);
            return new Tokens(data, raw, session.getExpiresAt());
        });
    }
    public Tokens reissue(String raw) {
        if (raw == null || raw.isBlank() || raw.length() > 100) throw new UnauthorizedException();
        return tx.execute(status -> {
            RefreshSession session = sessions.findLockedByHash(TokenService.hash(raw)).orElseThrow(UnauthorizedException::new);
            if (!session.active()) throw new UnauthorizedException();
            User user = users.findById(session.getUserId()).orElseThrow(UnauthorizedException::new);
            if (user.getStatus() != UserStatus.ACTIVE) throw new UnauthorizedException();
            String next = tokens.refresh(); session.rotate(TokenService.hash(next));
            return new Tokens(Map.of("accessToken", tokens.access(user, session.getId()), "tokenType", "Bearer"), next, session.getExpiresAt());
        });
    }
    public void logout(AuthPrincipal principal) {
        tx.executeWithoutResult(status -> {
            RefreshSession session = sessions.findLockedById(principal.sessionId()).orElseThrow(UnauthorizedException::new);
            if (!session.getUserId().equals(principal.userId())) throw new UnauthorizedException();
            session.revoke();
        });
    }
}
