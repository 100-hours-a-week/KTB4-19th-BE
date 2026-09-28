package com.homes.zipsai.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

class SwaggerExposureTest {

    @Nested
    @SpringBootTest(properties = {"springdoc.api-docs.enabled=false", "springdoc.swagger-ui.enabled=false"})
    @AutoConfigureMockMvc
    class Disabled {

        @Autowired
        MockMvcTester mockMvcTester;

        @Test
        @DisplayName("Swagger를 끄면 API 문서와 Swagger 화면에 접근할 수 없다")
        void hidesSwaggerWhenDisabled() {
            assertThat(mockMvcTester.get().uri("/v3/api-docs")).hasStatus(HttpStatus.NOT_FOUND);
            assertThat(mockMvcTester.get().uri("/swagger-ui/index.html")).hasStatus(HttpStatus.NOT_FOUND);
        }
    }

    @Nested
    @SpringBootTest(properties = {"springdoc.api-docs.enabled=true", "springdoc.swagger-ui.enabled=true"})
    @AutoConfigureMockMvc
    class Enabled {

        @Autowired
        MockMvcTester mockMvcTester;

        @Test
        @DisplayName("Swagger를 켜면 API 문서와 Swagger 화면에 접근할 수 있다")
        void exposesSwaggerWhenEnabled() {
            assertThat(mockMvcTester.get().uri("/v3/api-docs")).hasStatusOk();
            assertThat(mockMvcTester.get().uri("/swagger-ui/index.html")).hasStatusOk();
        }
    }
}
