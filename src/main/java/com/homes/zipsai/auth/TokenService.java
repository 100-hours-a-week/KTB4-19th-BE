package com.homes.zipsai.auth;

import com.homes.zipsai.user.User;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;

@Service
public class TokenService {
    private final JwtEncoder encoder;
    private final AuthProperties properties;
    private final SecureRandom random = new SecureRandom();
    public TokenService(JwtEncoder encoder, AuthProperties properties) { this.encoder = encoder; this.properties = properties; }
    public String access(User user, String sessionId) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer("zipsai").subject(user.getId().toString())
            .issuedAt(now).expiresAt(now.plus(properties.accessTtl()))
            .claim("sid", sessionId).claim("ver", user.getAuthVersion()).claim("role", user.getRole().name()).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }
    public String refresh() {
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    public static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
