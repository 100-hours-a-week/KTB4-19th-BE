package com.homes.zipsai.auth.service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import jakarta.annotation.PostConstruct;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.homes.zipsai.auth.domain.RefreshSession;
import com.homes.zipsai.auth.dto.LoginRequest;
import com.homes.zipsai.auth.dto.SignupRequest;
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

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AuthService {
    private final UserRepository users;
    private final TermsRepository terms;
    private final RefreshSessionRepository sessions;
    private final PasswordEncoder passwords;
    private final TokenService tokens;
    private final AuthProperties properties;
    private final TransactionTemplate tx;
    private String dummyHash;

    @PostConstruct
    void init() {
        dummyHash = passwords.encode("dummy-password");
    }

    public Long signup(SignupRequest request) {
        String email = UserInput.email(request.email());
        String password = UserInput.password(request.password());
        if (!password.equals(request.passwordConfirm())) {
            throw UserInput.invalid(
                    "passwordConfirm", Reason.PASSWORD_CONFIRMATION_MISMATCH);
        }
        String name = UserInput.name(request.userName());
        String phone = UserInput.phone(request.phone());
        var agreements = UserInput.agreements(request.agreements(), true);
        if (users.existsByEmail(email)) {
            throw duplicate();
        }
        String hash = passwords.encode(password);
        try {
            return tx.execute(status -> {
                User user = new User(email, hash, name, phone);
                agreements.forEach((type, agreed) -> user.agree(terms.getLatest(type), agreed));
                return users.saveAndFlush(user).getId();
            });
        } catch (DataIntegrityViolationException e) {
            if (users.existsByEmail(email)) {
                throw duplicate();
            }
            throw e;
        }
    }

    private ConflictException duplicate() {
        return new ConflictException(ConflictException.Reason.EMAIL_ALREADY_EXISTS);
    }

    public record Tokens(Map<String, Object> data, String refreshToken, Instant expiresAt) {
    }

    public Tokens login(LoginRequest loginRequest) {
        String email = UserInput.email(loginRequest.email());
        String password = UserInput.password(loginRequest.password());
        User user = users.findByEmail(email).orElse(null);
        boolean matches = passwords.matches(password, user == null ? dummyHash : user.getPassword());
        if (user == null || !matches || user.getStatus() != UserStatus.ACTIVE) {
            throw new InvalidCredentialsException();
        }
        return tx.execute(status -> {
            String raw = tokens.refresh();
            Instant expiresAt = Instant.now().plus(properties.refreshTtl());
            RefreshSession session = new RefreshSession(
                    UUID.randomUUID().toString(),
                    user.getId(),
                    TokenService.hash(raw),
                    expiresAt
            );
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
        if (raw == null || raw.isBlank() || raw.length() > 100) {
            throw new UnauthorizedException();
        }
        return tx.execute(status -> {
            RefreshSession session = sessions.findLockedByHash(TokenService.hash(raw))
                    .orElseThrow(UnauthorizedException::new);
            if (!session.active()) {
                throw new UnauthorizedException();
            }
            User user = users.findById(session.getUserId()).orElseThrow(UnauthorizedException::new);
            if (user.getStatus() != UserStatus.ACTIVE) {
                throw new UnauthorizedException();
            }
            String next = tokens.refresh();
            session.rotate(TokenService.hash(next));
            Map<String, Object> data = Map.of(
                    "accessToken", tokens.access(user, session.getId()),
                    "tokenType", "Bearer"
            );
            return new Tokens(data, next, session.getExpiresAt());
        });
    }

    public void logout(AuthPrincipal principal) {
        tx.executeWithoutResult(status -> {
            RefreshSession session = sessions.findLockedById(principal.sessionId())
                    .orElseThrow(UnauthorizedException::new);
            if (!session.getUserId().equals(principal.userId())) {
                throw new UnauthorizedException();
            }
            session.revoke();
        });
    }
}
