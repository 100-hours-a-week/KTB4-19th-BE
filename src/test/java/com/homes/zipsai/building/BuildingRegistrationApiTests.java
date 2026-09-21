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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

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
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        mvc.perform(withIp(post("/api/v1/managers/me/buildings")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("""
                                {"buildingName":"%s","roadAddress":"서울 강남구 역삼동 123-4"}
                                """.formatted("가".repeat(21))), ip))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void managerCanCreateOnlySelectedRoomsInBulk() throws Exception {
        String ip = nextRemoteIp();
        String token = managerToken(ip);
        long buildingId = registerBuilding(ip, token);

        mvc.perform(withIp(post("/api/v1/managers/me/buildings/{buildingId}/rooms", buildingId)
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("""
                                {"roomNos":["101","102","201"]}
                                """), ip))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.buildingId").value(buildingId))
                .andExpect(jsonPath("$.data.createdCount").value(3))
                .andExpect(jsonPath("$.data.rooms.length()").value(3))
                .andExpect(jsonPath("$.data.rooms[0].roomNo").value("101"))
                .andExpect(jsonPath("$.data.rooms[0].roomStatus").value("EMPTY"))
                .andExpect(jsonPath("$.data.rooms[2].roomNo").value("201"))
                .andExpect(jsonPath("$.data.rooms[2].roomStatus").value("EMPTY"));
    }

    @Test
    void duplicateRoomRejectsWholeBatchWithoutSavingOtherNumbers() throws Exception {
        String ip = nextRemoteIp();
        String token = managerToken(ip);
        long buildingId = registerBuilding(ip, token);

        mvc.perform(withIp(roomRequest(buildingId, token, "101"), ip))
                .andExpect(status().isCreated());

        mvc.perform(withIp(roomRequest(buildingId, token, "102", "101"), ip))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ROOM_ALREADY_EXISTS"));

        // 102이 앞선 중복 요청에서 일부 저장되지 않았으므로 단독 생성은 성공해야 합니다.
        mvc.perform(withIp(roomRequest(buildingId, token, "102"), ip))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.createdCount").value(1));
    }

    @Test
    void bulkRoomCreationRequiresRoomNumbersAndLimitsEachNumberToFiveCharacters() throws Exception {
        String ip = nextRemoteIp();
        String token = managerToken(ip);
        long buildingId = registerBuilding(ip, token);

        mvc.perform(withIp(post("/api/v1/managers/me/buildings/{buildingId}/rooms", buildingId)
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{}"), ip))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("MISSING_REQUIRED_FIELD"));

        mvc.perform(withIp(roomRequest(buildingId, token, "123456"), ip))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void onlyBuildingOwnerCanCreateItsRooms() throws Exception {
        String ownerIp = nextRemoteIp();
        String ownerToken = managerToken(ownerIp);
        long buildingId = registerBuilding(ownerIp, ownerToken);

        String otherManagerIp = nextRemoteIp();
        String otherManagerToken = managerToken(otherManagerIp);
        mvc.perform(withIp(roomRequest(buildingId, otherManagerToken, "101"), otherManagerIp))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        String residentIp = nextRemoteIp();
        String residentToken = userToken(residentIp, "RESIDENT");
        mvc.perform(withIp(roomRequest(buildingId, residentToken, "101"), residentIp))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void bulkRoomCreationRequiresAnExistingBuilding() throws Exception {
        String ip = nextRemoteIp();
        String token = managerToken(ip);

        mvc.perform(withIp(roomRequest(Long.MAX_VALUE, token, "101"), ip))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("BUILDING_NOT_FOUND"));
    }

    private long registerBuilding(String ip, String token) throws Exception {
        MvcResult response = mvc.perform(withIp(post("/api/v1/managers/me/buildings")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("""
                                {"buildingName":"테스트 건물","roadAddress":"서울 강남구 역삼동 123-4"}
                                """), ip))
                .andExpect(status().isCreated())
                .andReturn();
        return json.readTree(response.getResponse().getContentAsString())
                .path("data")
                .path("buildingId")
                .asLong();
    }

    private MockHttpServletRequestBuilder roomRequest(long buildingId, String token, String... roomNos) {
        String roomNosJson = java.util.Arrays.stream(roomNos)
                .map(number -> "\"" + number + "\"")
                .collect(java.util.stream.Collectors.joining(","));
        return post("/api/v1/managers/me/buildings/{buildingId}/rooms", buildingId)
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content("{\"roomNos\":[" + roomNosJson + "]}");
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
                .stringValue();
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
