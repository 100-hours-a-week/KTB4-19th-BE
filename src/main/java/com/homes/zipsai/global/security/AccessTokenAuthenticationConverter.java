package com.homes.zipsai.global.security;

import java.util.List;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import com.homes.zipsai.global.exception.ApiException;
import com.homes.zipsai.global.exception.UnauthorizedException;

/** Converts verified JWT claims into the principal used by the application. */
@Component
public class AccessTokenAuthenticationConverter
        implements Converter<Jwt, AbstractAuthenticationToken> {

    @Override
    public AbstractAuthenticationToken convert(Jwt token) {
        try {
            long userId = Long.parseLong(token.getSubject());
            String sessionId = token.getClaimAsString("sid");
            String role = token.getClaimAsString("role");

            if (sessionId == null || sessionId.isBlank()
                    || role == null || role.isBlank()) {
                throw new UnauthorizedException();
            }

            return new UsernamePasswordAuthenticationToken(
                    new AuthPrincipal(userId, sessionId),
                    null,
                    List.of(new SimpleGrantedAuthority("ROLE_" + role))
            );
        } catch (ApiException | IllegalArgumentException exception) {
            throw new OAuth2AuthenticationException(
                    new OAuth2Error("invalid_token")
            );
        }
    }
}
