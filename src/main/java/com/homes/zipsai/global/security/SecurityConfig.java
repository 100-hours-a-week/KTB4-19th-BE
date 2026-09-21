package com.homes.zipsai.global.security;

import java.nio.charset.StandardCharsets;
import java.util.List;

import javax.crypto.spec.SecretKeySpec;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfigurationSource;

import com.homes.zipsai.global.exception.ForbiddenException;
import com.homes.zipsai.global.exception.UnauthorizedException;
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
            AccessTokenAuthenticationConverter accessTokenAuthenticationConverter,
            CorsConfigurationSource corsConfigurationSource,
            SecurityErrorResponseWriter errorResponseWriter
    ) throws Exception {
        http.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
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
                                errorResponseWriter.write(response, new UnauthorizedException()))
                        .accessDeniedHandler((request, response, cause) ->
                                errorResponseWriter.write(response, new ForbiddenException())))
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .authenticationEntryPoint((request, response, cause) ->
                                errorResponseWriter.write(response, new UnauthorizedException()))
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(
                                accessTokenAuthenticationConverter)))
                .addFilterBefore(
                        new RequestGuard(properties, json),
                        UsernamePasswordAuthenticationFilter.class
            );
        return http.build();
    }

}
