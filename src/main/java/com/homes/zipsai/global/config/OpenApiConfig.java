package com.homes.zipsai.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

@Configuration
public class OpenApiConfig {

    private static final String BEARER_AUTH = "bearerAuth";

    @Bean
    public OpenAPI zipsaiOpenApi() {
        SecurityScheme bearer = new SecurityScheme()
            .type(SecurityScheme.Type.HTTP)
            .scheme("bearer")
            .bearerFormat("JWT")
            .description("POST /api/v1/auth/login 응답의 accessToken");
        return new OpenAPI()
            .info(new Info().title("집사이 API").version("v1"))
            .components(new Components().addSecuritySchemes(BEARER_AUTH, bearer))
            .addSecurityItem(new SecurityRequirement().addList(BEARER_AUTH));
    }
}
