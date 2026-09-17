package com.homes.zipsai.global.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

class SwaggerExposureTests {

    @Nested
    @SpringBootTest(properties = {"springdoc.api-docs.enabled=false", "springdoc.swagger-ui.enabled=false"})
    @AutoConfigureMockMvc
    class Disabled {

        @Autowired
        MockMvc mvc;

        @Test
        void swaggerIsNotExposedByDefault() throws Exception {
            mvc.perform(get("/v3/api-docs")).andExpect(status().isNotFound());
            mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isNotFound());
        }
    }

    @Nested
    @SpringBootTest(properties = {"springdoc.api-docs.enabled=true", "springdoc.swagger-ui.enabled=true"})
    @AutoConfigureMockMvc
    class Enabled {

        @Autowired
        MockMvc mvc;

        @Test
        void swaggerIsOpenWhenEnabled() throws Exception {
            mvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
            mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
        }
    }
}
