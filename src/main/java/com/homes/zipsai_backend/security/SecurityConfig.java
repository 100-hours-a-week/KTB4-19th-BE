package com.homes.zipsai_backend.security;

import com.homes.zipsai_backend.auth.*;
import com.homes.zipsai_backend.common.*;
import com.homes.zipsai_backend.user.*;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.*;
import tools.jackson.databind.ObjectMapper;

@Configuration @EnableMethodSecurity @EnableConfigurationProperties(AuthProperties.class)
public class SecurityConfig {
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }
    @Bean JwtEncoder jwtEncoder(AuthProperties p) { return new NimbusJwtEncoder(new ImmutableSecret<>(p.secret().getBytes(StandardCharsets.UTF_8))); }
    @Bean JwtDecoder jwtDecoder(AuthProperties p) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(new SecretKeySpec(p.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256")).macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer("zipsai")); return decoder;
    }
    @Bean SecurityFilterChain security(HttpSecurity http, AuthProperties p, ObjectMapper json, UserRepository users, RefreshSessionRepository sessions) throws Exception {
        http.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .csrf(c -> c.disable()).cors(c -> c.configurationSource(cors(p)))
            .formLogin(c -> c.disable()).httpBasic(c -> c.disable()).logout(c -> c.disable())
            .authorizeHttpRequests(a -> a
                .requestMatchers(org.springframework.http.HttpMethod.OPTIONS, "/**").permitAll()
                .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/v1/auth/signup", "/api/v1/auth/login", "/api/v1/auth/reissue").permitAll()
                .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/v1/users/email-availability", "/api/v1/terms", "/api/v1/terms/*").permitAll()
                .requestMatchers("/api/v1/managers/**").hasRole("MANAGER")
                .requestMatchers("/api/v1/residents/**").hasRole("RESIDENT")
                .anyRequest().authenticated())
            .exceptionHandling(e -> e
                .authenticationEntryPoint((request, response, ex) -> write(response, json, ApiException.unauthorized()))
                .accessDeniedHandler((request, response, ex) -> write(response, json, ApiException.forbidden())))
            .oauth2ResourceServer(o -> o
                .authenticationEntryPoint((request, response, ex) -> write(response, json, ApiException.unauthorized()))
                .jwt(j -> j.jwtAuthenticationConverter(jwt -> {
                    try {
                        long id = Long.parseLong(jwt.getSubject());
                        User user = users.findById(id).orElseThrow(ApiException::unauthorized);
                        RefreshSession session = sessions.findById(jwt.getClaimAsString("sid")).orElseThrow(ApiException::unauthorized);
                        Number version = jwt.getClaim("ver");
                        if (version == null || user.getAuthVersion() != version.longValue() || user.getStatus() != UserStatus.ACTIVE
                            || !session.active() || !session.getUserId().equals(id)) throw ApiException.unauthorized();
                        return new UsernamePasswordAuthenticationToken(new AuthPrincipal(id, session.getId()), null,
                            List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name())));
                    } catch (ApiException | IllegalArgumentException e) {
                        throw new OAuth2AuthenticationException(new OAuth2Error("invalid_token"));
                    }
                })))
            .addFilterBefore(new RequestGuard(p, json), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
    private CorsConfigurationSource cors(AuthProperties p) {
        CorsConfiguration c = new CorsConfiguration(); c.setAllowedOrigins(p.allowedOrigins()); c.setAllowCredentials(true);
        c.setAllowedMethods(List.of("GET", "POST", "PATCH", "DELETE", "OPTIONS"));
        c.setAllowedHeaders(List.of("Authorization", "Content-Type")); c.setExposedHeaders(List.of("Retry-After"));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource(); source.registerCorsConfiguration("/**", c); return source;
    }
    private void write(jakarta.servlet.http.HttpServletResponse response, ObjectMapper json, ApiException e) throws java.io.IOException {
        response.setStatus(e.status); response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(json.writeValueAsString(ApiResponse.error(e)));
    }
}
