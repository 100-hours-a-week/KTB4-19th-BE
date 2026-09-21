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

import com.homes.zipsai.user.domain.UserRole;

/** Builds the request principal using only the already-verified access-token claims. */
@Component
public class AccessTokenAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        try {
            long userId = Long.parseLong(jwt.getSubject());
            UserRole role = UserRole.valueOf(jwt.getClaimAsString("role"));
            if (userId <= 0) {
                throw new IllegalArgumentException("Invalid user id");
            }

            return new UsernamePasswordAuthenticationToken(
                    new AuthPrincipal(userId),
                    null,
                    List.of(new SimpleGrantedAuthority("ROLE_" + role.name()))
            );
        } catch (RuntimeException e) {
            throw new OAuth2AuthenticationException(new OAuth2Error("invalid_token"));
        }
    }
}
