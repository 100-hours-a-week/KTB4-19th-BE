package com.homes.zipsai.building;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.MvcResult;

import com.homes.zipsai.ZipsaiBackendApplication;

import tools.jackson.databind.ObjectMapper;

@SpringBootTest(classes = ZipsaiBackendApplication.class)
@AutoConfigureMockMvc
class BuildingRegistrationApiTests {
    private static final AtomicInteger REMOTE_IP = new AtomicInteger(1);

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Test
    void managerCanRegisterBuildingWithoutOptionalName() throws Exception {
        String ip = nextRemoteIp();
        String token = managerToken(ip);

        mvc.perform(withIp(post("/api/v1/managers/me/buildings")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("""
                                {"roadAddress":"서울 강남구 역삼동 123-4"}
                                """), ip))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.buildingId").isNumber())
                .andExpect(jsonPath("$.data.buildingName").value((Object) null))
                .andExpect(jsonPath("$.data.roadAddress").value("서울 강남구 역삼동 123-4"));
    }

    @Test
    void managerCannotRegisterMoreThanOneBuilding() throws Exception {
        String ip = nextRemoteIp();
        String token = managerToken(ip);
        MockHttpServletRequestBuilder request = post("/api/v1/managers/me/buildings")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content("""
                        {"buildingName":"역삼래미안 아파트","roadAddress":"서울 강남구 역삼동 123-4"}
                        """);

        mvc.perform(withIp(request, ip)).andExpect(status().isCreated());
        mvc.perform(withIp(request, ip))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("BUILDING_ALREADY_EXISTS"));
    }

    @Test
    void registrationRequiresManagerAuthentication() throws Exception {
        String ip = nextRemoteIp();

        mvc.perform(withIp(post("/api/v1/managers/me/buildings")
                        .contentType("application/json")
                        .content("""
                                {"roadAddress":"서울 강남구 역삼동 123-4"}
                                """), ip))
                .andExpect(status().isUnauthorized());

        mvc.perform(withIp(post("/api/v1/managers/me/buildings")
                        .header("Authorization", "Bearer " + userToken(ip, "RESIDENT"))
                        .contentType("application/json")
                        .content("""
                                {"roadAddress":"서울 강남구 역삼동 123-4"}
                                """), ip))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void registrationUsesCommonCodesForMissingAndInvalidFields() throws Exception {
        String ip = nextRemoteIp();
        String token = managerToken(ip);

        mvc.perform(withIp(post("/api/v1/managers/me/buildings")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{}"), ip))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("MISSING_REQUIRED_FIELD"));

        mvc.perform(withIp(post("/api/v1/managers/me/buildings")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("""
                                {"roadAddress":"%s"}
                                """.formatted("가".repeat(201))), ip))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        mvc.perform(withIp(post("/api/v1/managers/me/buildings")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("""
                                {"buildingName":"%s","roadAddress":"서울 강남구 역삼동 123-4"}
                                """.formatted("가".repeat(21))), ip))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    private String managerToken(String ip) throws Exception {
        return userToken(ip, "MANAGER");
    }

    private String userToken(String ip, String role) throws Exception {
        String email = UUID.randomUUID() + "@example.com";
        mvc.perform(withIp(post("/api/v1/auth/signup")
                        .contentType("application/json")
                        .content("""
                                {"email":"%s","password":"Asdf!12345","passwordConfirm":"Asdf!12345",
                                 "agreements":[
                                   {"termsType":"SERVICE","isAgreed":true},
                                   {"termsType":"PRIVACY","isAgreed":true}
                                 ]}
                                """.formatted(email)), ip))
                .andExpect(status().isCreated());

        MvcResult login = mvc.perform(withIp(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content("""
                                {"email":"%s","password":"Asdf!12345"}
                                """.formatted(email)), ip))
                .andExpect(status().isOk())
                .andReturn();
        String originalToken = accessToken(login);

        MvcResult selected = mvc.perform(withIp(patch("/api/v1/users/me")
                        .header("Authorization", "Bearer " + originalToken)
                        .contentType("application/json")
                        .content("""
                                {"userRole":"%s"}
                                """.formatted(role)), ip))
                .andExpect(status().isOk())
                .andReturn();
        return accessToken(selected);
    }

    private String accessToken(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString())
                .path("data")
                .path("accessToken")
                .asText();
    }

    private String nextRemoteIp() {
        return "10.100." + (REMOTE_IP.getAndIncrement() / 250) + "." + (REMOTE_IP.get() % 250 + 1);
    }

    private MockHttpServletRequestBuilder withIp(MockHttpServletRequestBuilder request, String ip) {
        return request.with(mockRequest -> {
            mockRequest.setRemoteAddr(ip);
            return mockRequest;
        });
    }
}
