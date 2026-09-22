package com.homes.zipsai.auth.service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.annotation.PostConstruct;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.homes.zipsai.auth.domain.RefreshSession;
import com.homes.zipsai.auth.dto.AgreementRequest;
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
    private final UserRepository userRepository;
    private final TermsRepository termsRepository;
    private final RefreshSessionRepository refreshSessionRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    private final AuthProperties authProperties;
    private final TransactionTemplate tx;
    private String dummyHash;

    @PostConstruct
    void init() {
        dummyHash = passwordEncoder.encode("dummy-password");
    }

    public Long signup(String emailInput, String passwordInput, String passwordConfirm,
                       String userNameInput, String phoneInput, List<AgreementRequest> agreementRequests) {
        String email = UserInput.email(emailInput);
        String password = UserInput.password(passwordInput);
        if (!password.equals(passwordConfirm)) {
            throw UserInput.invalid(
                    "passwordConfirm", Reason.PASSWORD_CONFIRMATION_MISMATCH);
        }
        String name = UserInput.name(userNameInput);
        String phone = UserInput.phone(phoneInput);
        var agreements = UserInput.agreements(agreementRequests, true);
        if (userRepository.existsByEmail(email)) {
            throw duplicate();
        }
        String hash = passwordEncoder.encode(password);
        try {
            return tx.execute(status -> {
                User user = new User(email, hash, name, phone);
                agreements.forEach((type, agreed) -> user.agree(termsRepository.getLatest(type), agreed));
                return userRepository.saveAndFlush(user).getId();
            });
        } catch (DataIntegrityViolationException e) {
            if (userRepository.existsByEmail(email)) {
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

    public Tokens login(String emailInput, String passwordInput) {
        String email = UserInput.email(emailInput);
        String password = UserInput.password(passwordInput);
        User user = userRepository.findByEmail(email).orElse(null);
        boolean matches = passwordEncoder.matches(password, user == null ? dummyHash : user.getPassword());
        if (user == null || !matches || user.getStatus() != UserStatus.ACTIVE) {
            throw new InvalidCredentialsException();
        }
        return tx.execute(status -> {
            String raw = tokenService.refresh();
            Instant expiresAt = Instant.now().plus(authProperties.refreshTtl());
            RefreshSession session = new RefreshSession(
                    UUID.randomUUID().toString(),
                    user.getId(),
                    TokenService.hash(raw),
                    expiresAt
            );
            refreshSessionRepository.save(session);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("accessToken", tokenService.access(user, session.getId()));
            data.put("tokenType", "Bearer");
            Map<String, Object> view = UserService.profile(user);
            view.remove("phone");
            view.put("email", user.getEmail());
            data.put("user", view);
            return new Tokens(data, raw, session.getExpiresAt());
        });
    }

    public Tokens reissue(String raw) {
        return tx.execute(status -> {
            RefreshSession session = refreshSessionRepository.findLockedByHash(TokenService.hash(raw))
                    .orElseThrow(UnauthorizedException::new);
            if (!session.active()) {
                throw new UnauthorizedException();
            }
            User user = userRepository.findById(session.getUserId()).orElseThrow(UnauthorizedException::new);
            if (user.getStatus() != UserStatus.ACTIVE) {
                throw new UnauthorizedException();
            }
            String next = tokenService.refresh();
            session.rotate(TokenService.hash(next));
            Map<String, Object> data = Map.of(
                    "accessToken", tokenService.access(user, session.getId()),
                    "tokenType", "Bearer"
            );
            return new Tokens(data, next, session.getExpiresAt());
        });
    }

    public void logout(AuthPrincipal principal) {
        tx.executeWithoutResult(status -> {
            RefreshSession session = refreshSessionRepository.findLockedById(principal.sessionId())
                    .orElseThrow(UnauthorizedException::new);
            if (!session.getUserId().equals(principal.userId())) {
                throw new UnauthorizedException();
            }
            session.revoke();
        });
    }
}
