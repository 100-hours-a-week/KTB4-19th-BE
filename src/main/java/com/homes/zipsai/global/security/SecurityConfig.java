package com.homes.zipsai.global.security;

import java.nio.charset.StandardCharsets;
import java.util.List;

import javax.crypto.spec.SecretKeySpec;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.homes.zipsai.auth.domain.RefreshSession;
import com.homes.zipsai.auth.repository.RefreshSessionRepository;
import com.homes.zipsai.global.exception.ApiException;
import com.homes.zipsai.global.exception.ForbiddenException;
import com.homes.zipsai.global.exception.UnauthorizedException;
import com.homes.zipsai.global.response.ApiResponse;
import com.homes.zipsai.user.domain.User;
import com.homes.zipsai.user.domain.UserStatus;
import com.homes.zipsai.user.repository.UserRepository;
import com.nimbusds.jose.jwk.source.ImmutableSecret;

import tools.jackson.databind.ObjectMapper;

@Configuration
@EnableMethodSecurity
@EnableConfigurationProperties(AuthProperties.class)
public class SecurityConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    JwtEncoder jwtEncoder(AuthProperties properties) {
        byte[] secret = properties.secret().getBytes(StandardCharsets.UTF_8);
        return new NimbusJwtEncoder(new ImmutableSecret<>(secret));
    }

    @Bean
    JwtDecoder jwtDecoder(AuthProperties properties) {
        byte[] secret = properties.secret().getBytes(StandardCharsets.UTF_8);
        SecretKeySpec secretKey = new SecretKeySpec(secret, "HmacSHA256");
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(secretKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer("zipsai"));
        return decoder;
    }

    @Bean
    SecurityFilterChain security(
            HttpSecurity http,
            AuthProperties properties,
            ObjectMapper json,
            UserRepository users,
            RefreshSessionRepository sessions
    ) throws Exception {
        http.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(cors(properties)))
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(org.springframework.http.HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(
                                org.springframework.http.HttpMethod.POST,
                                "/api/v1/auth/signup",
                                "/api/v1/auth/login",
                                "/api/v1/auth/reissue"
                        ).permitAll()
                        .requestMatchers(
                                org.springframework.http.HttpMethod.GET,
                                "/api/v1/users/email-availability",
                                "/api/v1/terms",
                                "/api/v1/terms/*"
                        ).permitAll()
                        .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        .requestMatchers("/api/v1/managers/**").hasRole("MANAGER")
                        .requestMatchers("/api/v1/residents/**").hasRole("RESIDENT")
                        .anyRequest().authenticated())
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint((request, response, cause) ->
                                write(response, json, new UnauthorizedException()))
                        .accessDeniedHandler((request, response, cause) ->
                                write(response, json, new ForbiddenException())))
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .authenticationEntryPoint((request, response, cause) ->
                                write(response, json, new UnauthorizedException()))
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(token -> {
                            try {
                                long userId = Long.parseLong(token.getSubject());
                                User user = users.findById(userId)
                                        .orElseThrow(UnauthorizedException::new);
                                String sessionId = token.getClaimAsString("sid");
                                RefreshSession session = sessions.findById(sessionId)
                                        .orElseThrow(UnauthorizedException::new);
                                Number version = token.getClaim("ver");
                                if (version == null
                                        || user.getAuthVersion() != version.longValue()
                                        || user.getStatus() != UserStatus.ACTIVE
                                        || !session.active()
                                        || !session.getUserId().equals(userId)) {
                                    throw new UnauthorizedException();
                                }
                                return new UsernamePasswordAuthenticationToken(
                                        new AuthPrincipal(userId, session.getId()),
                                        null,
                                        List.of(new SimpleGrantedAuthority(
                                                "ROLE_" + user.getRole().name()))
                        );
                            } catch (ApiException | IllegalArgumentException exception) {
                                throw new OAuth2AuthenticationException(
                                        new OAuth2Error("invalid_token")
                                );
                            }
                        })))
                .addFilterBefore(
                        new RequestGuard(properties, json),
                        UsernamePasswordAuthenticationFilter.class
            );
        return http.build();
    }

    private CorsConfigurationSource cors(AuthProperties properties) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(properties.allowedOrigins());
        configuration.setAllowCredentials(true);
        configuration.setAllowedMethods(List.of("GET", "POST", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        configuration.setExposedHeaders(List.of("Retry-After"));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    private void write(
            jakarta.servlet.http.HttpServletResponse response,
            ObjectMapper json,
            ApiException exception
    ) throws java.io.IOException {
        response.setStatus(exception.status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(json.writeValueAsString(ApiResponse.error(exception)));
    }
}
