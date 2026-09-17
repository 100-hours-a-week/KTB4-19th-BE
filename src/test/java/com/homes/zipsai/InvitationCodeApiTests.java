package com.homes.zipsai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
class InvitationCodeApiTests {
    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper json;

    private Account manager;

    @BeforeEach
    void setUp() throws Exception {
        manager = account("MANAGER");
    }

    @Test
    void managerCanIssueAndReissueWithThreeHourExpiry() throws Exception {
        Long roomId = createRoom(manager.userId(), "101호", "EMPTY", null);

        JsonNode first = data(issue(manager.token(), roomId)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.roomId").value(roomId))
                .andExpect(jsonPath("$.data.roomNo").value("101호"))
                .andExpect(jsonPath("$.data.roomStatus").value("INVITED"))
                .andExpect(jsonPath("$.data.roomStatusLabel").value("초대됨"))
                .andExpect(jsonPath("$.data.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.data.expiresAt").isNotEmpty())
                .andExpect(jsonPath("$.data.reissued").value(false))
                .andReturn());

        assertThat(first.path("reissued").asBoolean()).isFalse();
        assertThat(first.path("code").asText()).matches("[A-HJ-NP-Z2-9]{6}");
        assertExpiryIsThreeHours(first);
        Long firstCodeId = first.path("codeId").asLong();

        JsonNode second = data(issue(manager.token(), roomId)
                .andExpect(status().isCreated())
                .andReturn());

        assertThat(second.path("reissued").asBoolean()).isTrue();
        assertThat(second.path("codeId").asLong()).isNotEqualTo(firstCodeId);
        assertThat(second.path("code").asText()).isNotEqualTo(first.path("code").asText());
        assertExpiryIsThreeHours(second);
        assertThat(invitationStatus(firstCodeId)).isEqualTo("EXPIRED");
        assertThat(invitationStatus(second.path("codeId").asLong())).isEqualTo("ACTIVE");
        assertThat(roomStatus(roomId)).isEqualTo("INVITED");
    }

    @Test
    void rejectsOccupiedRoomAndAnotherManagersRoom() throws Exception {
        Account otherManager = account("MANAGER");
        Account resident = account("RESIDENT");
        Long occupiedRoomId = createRoom(manager.userId(), "101호", "LIVING", resident.userId());
        Long emptyRoomId = createRoom(manager.userId(), "102호", "EMPTY", null);

        issue(manager.token(), occupiedRoomId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ROOM_OCCUPIED"));
        issue(otherManager.token(), emptyRoomId)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        assertThat(roomStatus(emptyRoomId)).isEqualTo("EMPTY");
    }

    @Test
    void rejectsInvalidRoomIdsAndMissingRooms() throws Exception {
        issue(manager.token(), 0L)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details.violations[0].field").value("roomId"));
        mvc.perform(post(
                        "/api/v1/managers/me/rooms/{roomId}/invitation-codes",
                        "not-a-number"
                ).header("Authorization", "Bearer " + manager.token()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details.violations[0].field").value("roomId"));
        issue(manager.token(), Long.MAX_VALUE)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ROOM_NOT_FOUND"));
    }

    @Test
    void requiresAuthenticationForAllInvitationAndRoomOperations() throws Exception {
        assertUnauthorized(mvc.perform(post("/api/v1/managers/me/rooms/1/invitation-codes")));
        assertUnauthorized(mvc.perform(get("/api/v1/residents/me/invitation-codes/ABC234")));
        assertUnauthorized(mvc.perform(put("/api/v1/residents/me/room")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"ABC234\"}")));
        assertUnauthorized(mvc.perform(delete("/api/v1/managers/me/rooms/1/invitation-codes/1")));
        assertUnauthorized(mvc.perform(delete("/api/v1/managers/me/rooms/1/resident")));
    }

    @Test
    void residentCanValidateActiveCodeWithoutUsingIt() throws Exception {
        Account resident = account("RESIDENT");
        Long roomId = createRoom(manager.userId(), "101호", "EMPTY", null);
        JsonNode issued = data(issue(manager.token(), roomId).andExpect(status().isCreated()).andReturn());

        mvc.perform(get(
                        "/api/v1/residents/me/invitation-codes/{code}",
                        " " + issued.path("code").asText().toLowerCase() + " "
                ).header("Authorization", "Bearer " + resident.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.buildingName").value("A타워"))
                .andExpect(jsonPath("$.data.roomNo").value("101호"))
                .andExpect(jsonPath("$.data.managerName").doesNotExist());

        assertThat(invitationStatus(issued.path("codeId").asLong())).isEqualTo("ACTIVE");
        assertThat(roomStatus(roomId)).isEqualTo("INVITED");
    }

    @Test
    void rejectsInvalidMissingUsedAndExpiredCodes() throws Exception {
        Account resident = account("RESIDENT");
        Long roomId = createRoom(manager.userId(), "101호", "EMPTY", null);
        JsonNode issued = data(issue(manager.token(), roomId).andExpect(status().isCreated()).andReturn());
        Long codeId = issued.path("codeId").asLong();

        mvc.perform(get("/api/v1/residents/me/invitation-codes/{code}", "ABC1EF")
                        .header("Authorization", "Bearer " + resident.token()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        mvc.perform(get("/api/v1/residents/me/invitation-codes/{code}", "ZZZZZZ")
                        .header("Authorization", "Bearer " + resident.token()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("INVITATION_CODE_NOT_FOUND"));

        jdbc.update("update Invitation_codes set status='USED' where code_id=?", codeId);
        mvc.perform(get("/api/v1/residents/me/invitation-codes/{code}", issued.path("code").asText())
                        .header("Authorization", "Bearer " + resident.token()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVITATION_CODE_ALREADY_USED"));

        jdbc.update(
                "update Invitation_codes set status='ACTIVE', expires_at=current_timestamp where code_id=?",
                codeId
        );
        mvc.perform(get("/api/v1/residents/me/invitation-codes/{code}", issued.path("code").asText())
                        .header("Authorization", "Bearer " + resident.token()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVITATION_CODE_EXPIRED"));

        assertThat(invitationStatus(codeId)).isEqualTo("EXPIRED");
        assertThat(roomStatus(roomId)).isEqualTo("INVITED");
    }

    @Test
    void onlyResidentsCanValidateInvitationCodes() throws Exception {
        Long roomId = createRoom(manager.userId(), "101호", "EMPTY", null);
        JsonNode issued = data(issue(manager.token(), roomId).andExpect(status().isCreated()).andReturn());

        mvc.perform(get("/api/v1/residents/me/invitation-codes/{code}", issued.path("code").asText())
                        .header("Authorization", "Bearer " + manager.token()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void residentCanConnectToInvitedRoomWithNormalizedCode() throws Exception {
        Account resident = account("RESIDENT");
        Long roomId = createRoom(manager.userId(), "101호", "EMPTY", null);
        JsonNode issued = data(issue(manager.token(), roomId).andExpect(status().isCreated()).andReturn());

        connect(resident.token(), " " + issued.path("code").asText().toLowerCase() + " ")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.buildingName").value("A타워"))
                .andExpect(jsonPath("$.data.roomNo").value("101호"));

        assertThat(roomStatus(roomId)).isEqualTo("LIVING");
        assertThat(roomResident(roomId)).isEqualTo(resident.userId());
        assertThat(invitationStatus(issued.path("codeId").asLong())).isEqualTo("USED");
    }

    @Test
    void rejectsInvalidConnectionRequestsAndAlreadyConnectedResident() throws Exception {
        Account resident = account("RESIDENT");
        Long firstRoomId = createRoom(manager.userId(), "101호", "EMPTY", null);
        JsonNode first = data(issue(manager.token(), firstRoomId).andExpect(status().isCreated()).andReturn());

        mvc.perform(put("/api/v1/residents/me/room")
                        .header("Authorization", "Bearer " + resident.token()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("MISSING_REQUIRED_FIELD"));
        mvc.perform(put("/api/v1/residents/me/room")
                        .header("Authorization", "Bearer " + resident.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("MISSING_REQUIRED_FIELD"));
        connect(resident.token(), "   ")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details.violations[0].field").value("code"));
        connect(resident.token(), "ABC1EF")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        connect(resident.token(), "ZZZZZZ")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("INVITATION_CODE_NOT_FOUND"));

        connect(resident.token(), first.path("code").asText()).andExpect(status().isOk());

        Long secondRoomId = createRoom(manager.userId(), "102호", "EMPTY", null);
        JsonNode second = data(issue(manager.token(), secondRoomId).andExpect(status().isCreated()).andReturn());
        connect(resident.token(), second.path("code").asText())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ROOM_CONNECTION_CONFLICT"));
        assertThat(roomStatus(secondRoomId)).isEqualTo("INVITED");
        assertThat(invitationStatus(second.path("codeId").asLong())).isEqualTo("ACTIVE");
    }

    @Test
    void rejectsExpiredOrUsedCodeOnConnection() throws Exception {
        Account resident = account("RESIDENT");
        Long roomId = createRoom(manager.userId(), "101호", "EMPTY", null);
        JsonNode issued = data(issue(manager.token(), roomId).andExpect(status().isCreated()).andReturn());
        Long codeId = issued.path("codeId").asLong();

        jdbc.update("update Invitation_codes set expires_at=current_timestamp where code_id=?", codeId);
        connect(resident.token(), issued.path("code").asText())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVITATION_CODE_EXPIRED"));
        assertThat(invitationStatus(codeId)).isEqualTo("EXPIRED");
        assertThat(roomStatus(roomId)).isEqualTo("INVITED");

        jdbc.update("update Invitation_codes set status='USED' where code_id=?", codeId);
        connect(resident.token(), issued.path("code").asText())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ROOM_CONNECTION_CONFLICT"));
    }

    @Test
    void onlyResidentsCanConnectRoom() throws Exception {
        Long roomId = createRoom(manager.userId(), "101호", "EMPTY", null);
        JsonNode issued = data(issue(manager.token(), roomId).andExpect(status().isCreated()).andReturn());

        connect(manager.token(), issued.path("code").asText())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void managerCanMoveOutResidentAndIssueNewInvitation() throws Exception {
        Account resident = account("RESIDENT");
        Long roomId = createRoom(manager.userId(), "101호", "LIVING", resident.userId());

        moveOut(manager.token(), roomId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(nullValue()));

        assertThat(roomStatus(roomId)).isEqualTo("EMPTY");
        assertThat(roomResident(roomId)).isNull();

        issue(manager.token(), roomId)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.roomStatus").value("INVITED"));
    }

    @Test
    void rejectsUnauthorizedInvalidAndEmptyRoomMoveOut() throws Exception {
        Account otherManager = account("MANAGER");
        Account resident = account("RESIDENT");
        Long livingRoomId = createRoom(manager.userId(), "101호", "LIVING", resident.userId());
        Long emptyRoomId = createRoom(manager.userId(), "102호", "EMPTY", null);

        moveOut(otherManager.token(), livingRoomId)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        moveOut(manager.token(), emptyRoomId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("RESIDENT_NOT_FOUND_IN_ROOM"));
        moveOut(manager.token(), 0L)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        moveOut(manager.token(), Long.MAX_VALUE)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ROOM_NOT_FOUND"));
        moveOut(resident.token(), livingRoomId)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void managerCanCancelActiveInvitation() throws Exception {
        Account resident = account("RESIDENT");
        Long roomId = createRoom(manager.userId(), "101호", "EMPTY", null);
        JsonNode issued = data(issue(manager.token(), roomId).andExpect(status().isCreated()).andReturn());

        cancel(manager.token(), roomId, issued.path("codeId").asLong())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(nullValue()));

        assertThat(invitationStatus(issued.path("codeId").asLong())).isEqualTo("EXPIRED");
        assertThat(roomStatus(roomId)).isEqualTo("EMPTY");
        mvc.perform(get("/api/v1/residents/me/invitation-codes/{code}", issued.path("code").asText())
                        .header("Authorization", "Bearer " + resident.token()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVITATION_CODE_EXPIRED"));
    }

    @Test
    void rejectsUnauthorizedWrongRoomAndInactiveInvitationCancellation() throws Exception {
        Account otherManager = account("MANAGER");
        Long roomId = createRoom(manager.userId(), "101호", "EMPTY", null);
        Long otherRoomId = createRoom(manager.userId(), "102호", "EMPTY", null);
        JsonNode issued = data(issue(manager.token(), roomId).andExpect(status().isCreated()).andReturn());

        cancel(otherManager.token(), roomId, issued.path("codeId").asLong())
                .andExpect(status().isForbidden());
        cancel(manager.token(), roomId, 0L)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details.violations[0].field").value("codeId"));
        cancel(manager.token(), otherRoomId, issued.path("codeId").asLong())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("INVITATION_CODE_NOT_FOUND"));

        jdbc.update("update Invitation_codes set status='USED' where code_id=?", issued.path("codeId").asLong());
        cancel(manager.token(), roomId, issued.path("codeId").asLong())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVITATION_CANCEL_NOT_ALLOWED"));
        assertThat(roomStatus(roomId)).isEqualTo("INVITED");
    }

    private ResultActions issue(String token, Long roomId) throws Exception {
        return mvc.perform(post(
                        "/api/v1/managers/me/rooms/{roomId}/invitation-codes",
                        roomId
                ).header("Authorization", "Bearer " + token));
    }

    private ResultActions cancel(String token, Long roomId, Long codeId) throws Exception {
        return mvc.perform(delete(
                        "/api/v1/managers/me/rooms/{roomId}/invitation-codes/{codeId}",
                        roomId,
                        codeId
                ).header("Authorization", "Bearer " + token));
    }

    private ResultActions moveOut(String token, Long roomId) throws Exception {
        return mvc.perform(delete(
                        "/api/v1/managers/me/rooms/{roomId}/resident",
                        roomId
                ).header("Authorization", "Bearer " + token));
    }

    private ResultActions connect(String token, String code) throws Exception {
        return mvc.perform(put("/api/v1/residents/me/room")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"" + code + "\"}"));
    }

    private void assertUnauthorized(ResultActions action) throws Exception {
        action.andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    private JsonNode data(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString()).path("data");
    }

    private void assertExpiryIsThreeHours(JsonNode data) {
        Timestamp createdAt = jdbc.queryForObject(
                "select created_at from Invitation_codes where code_id=?",
                Timestamp.class,
                data.path("codeId").asLong()
        );
        Timestamp expiresAt = jdbc.queryForObject(
                "select expires_at from Invitation_codes where code_id=?",
                Timestamp.class,
                data.path("codeId").asLong()
        );
        assertThat(Duration.between(createdAt.toLocalDateTime(), expiresAt.toLocalDateTime()).toSeconds())
                .isBetween(10_799L, 10_800L);
    }

    private Account account(String role) throws Exception {
        String email = UUID.randomUUID() + "@example.com";
        mvc.perform(post("/api/v1/auth/signup")
                        .contentType("application/json")
                        .content("""
                                {"email":"%s","password":"Asdf!12345","passwordConfirm":"Asdf!12345",
                                 "agreements":[
                                     {"termsType":"SERVICE","isAgreed":true},
                                     {"termsType":"PRIVACY","isAgreed":true},
                                     {"termsType":"MARKETING","isAgreed":false}
                                 ]}
                                """.formatted(email)))
                .andExpect(status().isCreated());
        String loginToken = loginToken(email);
        MvcResult selected = mvc.perform(MockMvcRequestBuilders.patch("/api/v1/users/me")
                        .header("Authorization", "Bearer " + loginToken)
                        .contentType("application/json")
                        .content("{\"userRole\":\"" + role + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String token = json.readTree(selected.getResponse().getContentAsString())
                .path("data")
                .path("accessToken")
                .asText();
        Long userId = jdbc.queryForObject("select user_id from Users where email=?", Long.class, email);
        return new Account(userId, token);
    }

    private String loginToken(String email) throws Exception {
        MvcResult result = mvc.perform(MockMvcRequestBuilders.post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content("""
                                {"email":"%s","password":"Asdf!12345"}
                                """.formatted(email)))
                .andExpect(status().isOk())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString())
                .path("data")
                .path("accessToken")
                .asText();
    }

    private Long createRoom(Long managerId, String roomNo, String roomStatus, Long residentId) {
        List<Long> buildingIds = jdbc.queryForList(
                "select building_id from Buildings where user_id=?",
                Long.class,
                managerId
        );
        Long buildingId;
        if (buildingIds.isEmpty()) {
            jdbc.update(
                    "insert into Buildings (user_id, building_name, road_address, created_at, updated_at) "
                            + "values (?, 'A타워', '서울시', current_timestamp, current_timestamp)",
                    managerId
            );
            buildingId = jdbc.queryForObject(
                    "select building_id from Buildings where user_id=?",
                    Long.class,
                    managerId
            );
        } else {
            buildingId = buildingIds.get(0);
        }
        jdbc.update(
                "insert into Rooms (building_id, user_id, room_status, room_no, created_at, updated_at) "
                        + "values (?, ?, ?, ?, current_timestamp, current_timestamp)",
                buildingId,
                residentId,
                roomStatus,
                roomNo
        );
        return jdbc.queryForObject("select room_id from Rooms where building_id=? and room_no=?", Long.class,
                buildingId, roomNo);
    }

    private String invitationStatus(Long codeId) {
        return jdbc.queryForObject("select status from Invitation_codes where code_id=?", String.class, codeId);
    }

    private String roomStatus(Long roomId) {
        return jdbc.queryForObject("select room_status from Rooms where room_id=?", String.class, roomId);
    }

    private Long roomResident(Long roomId) {
        return jdbc.queryForObject("select user_id from Rooms where room_id=?", Long.class, roomId);
    }

    private record Account(Long userId, String token) {
    }
}
