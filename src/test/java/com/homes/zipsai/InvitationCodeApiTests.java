package com.homes.zipsai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Locale;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import com.homes.zipsai.auth.service.TokenService;
import com.homes.zipsai.building.domain.Building;
import com.homes.zipsai.building.domain.Room;
import com.homes.zipsai.building.domain.RoomStatus;
import com.homes.zipsai.building.repository.BuildingRepository;
import com.homes.zipsai.building.repository.RoomRepository;
import com.homes.zipsai.user.domain.User;
import com.homes.zipsai.user.domain.UserRole;
import com.homes.zipsai.user.repository.UserRepository;
import com.jayway.jsonpath.JsonPath;

@SpringBootTest(classes = ZipsaiBackendApplication.class)
@AutoConfigureMockMvc
@DisplayName("초대·세대 연결 API")
class InvitationCodeApiTests {
    private static final String ISSUE_PATH = "/api/v1/managers/me/rooms/{roomId}/invitation-codes";
    private static final String VALIDATE_PATH = "/api/v1/residents/me/invitation-codes/{code}";
    private static final String CONNECT_PATH = "/api/v1/residents/me/room";
    private static final String CANCEL_PATH = "/api/v1/managers/me/rooms/{roomId}/invitation-codes/{codeId}";
    private static final String MOVE_OUT_PATH = "/api/v1/managers/me/rooms/{roomId}/resident";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TokenService tokenService;

    @Autowired
    UserRepository userRepository;

    @Autowired
    BuildingRepository buildingRepository;

    @Autowired
    RoomRepository roomRepository;

    private Account manager;
    private Building building;

    @BeforeEach
    void setUpManagerAndBuilding() {
        manager = account(UserRole.MANAGER, "김관리");
        building = building(manager.user(), "A타워");
    }

    @Test
    @DisplayName("공실에서 발급하고 초대 중 재발급하면 이전 활성 코드를 만료한다")
    void issuesAndReissuesCodesWithinRoomStateContract() throws Exception {
        Room room = room(building, "101호", RoomStatus.EMPTY, null);

        String firstCode = code(issue(manager, room.getId())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.code").isNotEmpty())
                .andExpect(jsonPath("$.data.reissued").value(false))
                .andReturn());
        assertThat(firstCode).matches("[A-HJ-NP-Z2-9]{6}");
        assertThat(roomStatus(room.getId())).isEqualTo("INVITED");
        assertThat(invitationStatus(firstCode)).isEqualTo("ACTIVE");

        String secondCode = code(issue(manager, room.getId())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.code").isNotEmpty())
                .andExpect(jsonPath("$.data.reissued").value(true))
                .andReturn());

        assertThat(secondCode).matches("[A-HJ-NP-Z2-9]{6}").isNotEqualTo(firstCode);
        assertThat(invitationStatus(firstCode)).isEqualTo("EXPIRED");
        assertThat(invitationStatus(secondCode)).isEqualTo("ACTIVE");
        assertThat(activeInvitationCount(room.getId())).isEqualTo(1);
        assertThat(roomStatus(room.getId())).isEqualTo("INVITED");
    }

    @Test
    @DisplayName("입주 중이거나 다른 관리자가 소유한 호실에는 코드를 발급하지 않는다")
    void rejectsIssueForOccupiedOrUnownedRooms() throws Exception {
        Account resident = account(UserRole.RESIDENT, "이입주");
        Room occupied = room(building, "101호", RoomStatus.LIVING, resident.user());

        issue(manager, occupied.getId())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ROOM_OCCUPIED"));
        assertThat(roomStatus(occupied.getId())).isEqualTo("LIVING");
        assertThat(activeInvitationCount(occupied.getId())).isZero();

        Account otherManager = account(UserRole.MANAGER, "다른관리자");
        Building otherBuilding = building(otherManager.user(), "B타워");
        Room otherRoom = room(otherBuilding, "201호", RoomStatus.EMPTY, null);

        issue(manager, otherRoom.getId())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        assertThat(roomStatus(otherRoom.getId())).isEqualTo("EMPTY");
        assertThat(activeInvitationCount(otherRoom.getId())).isZero();
    }

    @Test
    @DisplayName("잘못되거나 존재하지 않는 호실 ID를 거부한다")
    void rejectsInvalidAndUnknownRoomIds() throws Exception {
        issue(manager, 0L)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details.violations[0].field").value("roomId"));
        mvc.perform(post(ISSUE_PATH, "not-a-number").header("Authorization", manager.authorization()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        issue(manager, Long.MAX_VALUE)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ROOM_NOT_FOUND"));
    }

    @Test
    @DisplayName("검증은 공백·소문자를 정규화하고 코드를 사용 처리하지 않는다")
    void validatesNormalizedCodeWithoutStateChanges() throws Exception {
        Account resident = account(UserRole.RESIDENT, "이입주");
        Room room = room(building, "101호", RoomStatus.EMPTY, null);
        String issuedCode = code(issue(manager, room.getId()).andExpect(status().isCreated()).andReturn());

        mvc.perform(get(VALIDATE_PATH, " " + issuedCode.toLowerCase(Locale.ROOT) + " ")
                        .header("Authorization", resident.authorization()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.buildingName").value("A타워"))
                .andExpect(jsonPath("$.data.roomNo").value("101호"))
                .andExpect(jsonPath("$.data.managerName").doesNotExist())
                .andExpect(jsonPath("$.data.roadAddress").doesNotExist())
                .andExpect(jsonPath("$.data.managerPhone").doesNotExist());

        assertThat(invitationStatus(issuedCode)).isEqualTo("ACTIVE");
        assertThat(roomStatus(room.getId())).isEqualTo("INVITED");
    }

    @Test
    @DisplayName("형식 오류·미존재·사용·만료 코드는 검증에 실패한다")
    void rejectsInvalidUnknownUsedAndExpiredValidationCodes() throws Exception {
        Account resident = account(UserRole.RESIDENT, "이입주");
        mvc.perform(get(VALIDATE_PATH, "ABC1EF").header("Authorization", resident.authorization()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details.violations[0].field").value("code"));
        mvc.perform(get(VALIDATE_PATH, "ZZZZZZ").header("Authorization", resident.authorization()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("INVITATION_CODE_NOT_FOUND"));

        Room usedRoom = room(building, "101호", RoomStatus.EMPTY, null);
        String usedCode = code(issue(manager, usedRoom.getId()).andExpect(status().isCreated()).andReturn());
        connect(resident, usedCode).andExpect(status().isOk());
        mvc.perform(get(VALIDATE_PATH, usedCode).header("Authorization", resident.authorization()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVITATION_CODE_ALREADY_USED"));

        Room expiredRoom = room(building, "102호", RoomStatus.EMPTY, null);
        String expiredCode = code(issue(manager, expiredRoom.getId()).andExpect(status().isCreated()).andReturn());
        expire(expiredCode);
        mvc.perform(get(VALIDATE_PATH, expiredCode).header("Authorization", resident.authorization()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVITATION_CODE_EXPIRED"));
        assertThat(invitationStatus(expiredCode)).isEqualTo("EXPIRED");
        assertThat(roomStatus(expiredRoom.getId())).isEqualTo("INVITED");
    }

    @Test
    @DisplayName("역할 미선택 사용자는 코드 연결 시 입주민으로 전환된다")
    void connectsResidentAndConsumesInvitation() throws Exception {
        Account resident = account(UserRole.NONE, "이입주");
        Room room = room(building, "101호", RoomStatus.EMPTY, null);
        String issuedCode = code(issue(manager, room.getId()).andExpect(status().isCreated()).andReturn());

        connect(resident, " " + issuedCode.toLowerCase(Locale.ROOT) + " ")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.buildingName").value("A타워"))
                .andExpect(jsonPath("$.data.roomNo").value("101호"));

        assertThat(invitationStatus(issuedCode)).isEqualTo("USED");
        assertThat(roomStatus(room.getId())).isEqualTo("LIVING");
        assertThat(roomResident(room.getId())).isEqualTo(resident.user().getId());
        assertThat(userRepository.findById(resident.user().getId()).orElseThrow().getRole())
                .isEqualTo(UserRole.RESIDENT);
    }

    @Test
    @DisplayName("필수값 누락·잘못된·미존재·만료 코드는 연결에 사용할 수 없다")
    void rejectsInvalidAndExpiredConnectionRequests() throws Exception {
        Account resident = account(UserRole.RESIDENT, "이입주");
        mvc.perform(put(CONNECT_PATH).header("Authorization", resident.authorization()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("MISSING_REQUIRED_FIELD"));
        mvc.perform(put(CONNECT_PATH)
                        .header("Authorization", resident.authorization())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("MISSING_REQUIRED_FIELD"));
        connect(resident, "   ")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details.violations[0].field").value("code"));
        connect(resident, "ABC1EF")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        connect(resident, "ZZZZZZ")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("INVITATION_CODE_NOT_FOUND"));

        Room room = room(building, "101호", RoomStatus.EMPTY, null);
        String expiredCode = code(issue(manager, room.getId()).andExpect(status().isCreated()).andReturn());
        expire(expiredCode);
        connect(resident, expiredCode)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVITATION_CODE_EXPIRED"));
        assertThat(invitationStatus(expiredCode)).isEqualTo("EXPIRED");
        assertThat(roomStatus(room.getId())).isEqualTo("INVITED");
    }

    @Test
    @DisplayName("이미 입주한 주민의 추가 연결과 사용된 코드 재사용을 거부한다")
    void rejectsSecondRoomConnectionAndCodeReuse() throws Exception {
        Account resident = account(UserRole.RESIDENT, "이입주");
        Room firstRoom = room(building, "101호", RoomStatus.EMPTY, null);
        String firstCode = code(issue(manager, firstRoom.getId()).andExpect(status().isCreated()).andReturn());
        connect(resident, firstCode).andExpect(status().isOk());

        connect(resident, firstCode)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ROOM_CONNECTION_CONFLICT"));

        Room secondRoom = room(building, "102호", RoomStatus.EMPTY, null);
        String secondCode = code(issue(manager, secondRoom.getId()).andExpect(status().isCreated()).andReturn());
        connect(resident, secondCode)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ROOM_CONNECTION_CONFLICT"));

        assertThat(invitationStatus(firstCode)).isEqualTo("USED");
        assertThat(invitationStatus(secondCode)).isEqualTo("ACTIVE");
        assertThat(roomStatus(firstRoom.getId())).isEqualTo("LIVING");
        assertThat(roomResident(firstRoom.getId())).isEqualTo(resident.user().getId());
        assertThat(roomStatus(secondRoom.getId())).isEqualTo("INVITED");
        assertThat(roomResident(secondRoom.getId())).isNull();
    }

    @Test
    @DisplayName("활성 초대를 취소하면 코드는 만료되고 호실은 공실이 된다")
    void cancelsInvitationAndLeavesRoomEmpty() throws Exception {
        Account resident = account(UserRole.RESIDENT, "이입주");
        Room room = room(building, "101호", RoomStatus.EMPTY, null);
        String issuedCode = code(issue(manager, room.getId()).andExpect(status().isCreated()).andReturn());

        cancel(manager, room.getId(), invitationId(issuedCode))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(nullValue()));

        assertThat(invitationStatus(issuedCode)).isEqualTo("EXPIRED");
        assertThat(roomStatus(room.getId())).isEqualTo("EMPTY");
        mvc.perform(get(VALIDATE_PATH, issuedCode).header("Authorization", resident.authorization()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVITATION_CODE_EXPIRED"));
        connect(resident, issuedCode)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVITATION_CODE_EXPIRED"));
        assertThat(roomStatus(room.getId())).isEqualTo("EMPTY");
    }

    @Test
    @DisplayName("다른 관리자·다른 호실·비활성 코드의 취소를 거부한다")
    void rejectsUnauthorizedWrongRoomAndInactiveCancellations() throws Exception {
        Room room = room(building, "101호", RoomStatus.EMPTY, null);
        Room otherRoom = room(building, "102호", RoomStatus.EMPTY, null);
        String issuedCode = code(issue(manager, room.getId()).andExpect(status().isCreated()).andReturn());
        Long codeId = invitationId(issuedCode);

        Account otherManager = account(UserRole.MANAGER, "다른관리자");
        cancel(otherManager, room.getId(), codeId)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        cancel(manager, otherRoom.getId(), codeId)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("INVITATION_CODE_NOT_FOUND"));
        cancel(manager, room.getId(), 0L)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details.violations[0].field").value("codeId"));
        cancel(manager, 0L, codeId)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details.violations[0].field").value("roomId"));

        jdbc.update("UPDATE Invitation_codes SET status = 'USED' WHERE code_id = ?", codeId);
        cancel(manager, room.getId(), codeId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVITATION_CANCEL_NOT_ALLOWED"));
        assertThat(roomStatus(room.getId())).isEqualTo("INVITED");
    }

    @Test
    @DisplayName("퇴실 처리로 연결을 해제하면 호실에 새 초대를 발급할 수 있다")
    void movesOutResidentBeforeIssuingNewCode() throws Exception {
        Account resident = account(UserRole.RESIDENT, "이입주");
        Room room = room(building, "101호", RoomStatus.LIVING, resident.user());

        mvc.perform(delete(MOVE_OUT_PATH, room.getId()).header("Authorization", manager.authorization()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(nullValue()));
        assertThat(roomStatus(room.getId())).isEqualTo("EMPTY");
        assertThat(roomResident(room.getId())).isNull();

        issue(manager, room.getId()).andExpect(status().isCreated());
        assertThat(roomStatus(room.getId())).isEqualTo("INVITED");
    }

    @Test
    @DisplayName("무권한·잘못된·공실 호실의 퇴실 요청을 거부한다")
    void rejectsUnauthorizedInvalidAndEmptyRoomMoveOut() throws Exception {
        Account resident = account(UserRole.RESIDENT, "이입주");
        Account otherManager = account(UserRole.MANAGER, "다른관리자");
        Room occupied = room(building, "101호", RoomStatus.LIVING, resident.user());
        Room empty = room(building, "102호", RoomStatus.EMPTY, null);

        mvc.perform(delete(MOVE_OUT_PATH, occupied.getId()).header("Authorization", otherManager.authorization()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        mvc.perform(delete(MOVE_OUT_PATH, empty.getId()).header("Authorization", manager.authorization()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("RESIDENT_NOT_FOUND_IN_ROOM"));
        mvc.perform(delete(MOVE_OUT_PATH, 0).header("Authorization", manager.authorization()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        mvc.perform(delete(MOVE_OUT_PATH, Long.MAX_VALUE).header("Authorization", manager.authorization()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ROOM_NOT_FOUND"));
        mvc.perform(delete(MOVE_OUT_PATH, occupied.getId()).header("Authorization", resident.authorization()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        assertThat(roomStatus(occupied.getId())).isEqualTo("LIVING");
        assertThat(roomResident(occupied.getId())).isEqualTo(resident.user().getId());
    }

    @Test
    @DisplayName("모든 초대·세대 연결 API는 인증과 역할을 확인한다")
    void requiresAuthenticationAndCorrectRoles() throws Exception {
        Room room = room(building, "101호", RoomStatus.EMPTY, null);
        String issuedCode = code(issue(manager, room.getId()).andExpect(status().isCreated()).andReturn());
        Long codeId = invitationId(issuedCode);
        Account resident = account(UserRole.RESIDENT, "이입주");

        assertUnauthorized(mvc.perform(post(ISSUE_PATH, room.getId())));
        assertUnauthorized(mvc.perform(get(VALIDATE_PATH, issuedCode)));
        assertUnauthorized(mvc.perform(put(CONNECT_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"" + issuedCode + "\"}")));
        assertUnauthorized(mvc.perform(delete(CANCEL_PATH, room.getId(), codeId)));
        assertUnauthorized(mvc.perform(delete(MOVE_OUT_PATH, room.getId())));

        issue(resident, room.getId())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        mvc.perform(get(VALIDATE_PATH, issuedCode).header("Authorization", manager.authorization()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        connect(manager, issuedCode)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        cancel(resident, room.getId(), codeId)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        mvc.perform(delete(MOVE_OUT_PATH, room.getId()).header("Authorization", resident.authorization()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        assertThat(invitationStatus(issuedCode)).isEqualTo("ACTIVE");
        assertThat(roomStatus(room.getId())).isEqualTo("INVITED");
    }

    private ResultActions issue(Account account, Long roomId) throws Exception {
        return mvc.perform(post(ISSUE_PATH, roomId).header("Authorization", account.authorization()));
    }

    private ResultActions connect(Account account, String code) throws Exception {
        return mvc.perform(put(CONNECT_PATH)
                .header("Authorization", account.authorization())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"" + code + "\"}"));
    }

    private ResultActions cancel(Account account, Long roomId, Long codeId) throws Exception {
        return mvc.perform(delete(CANCEL_PATH, roomId, codeId)
                .header("Authorization", account.authorization()));
    }

    private String code(MvcResult result) throws Exception {
        return (String) JsonPath.read(result.getResponse().getContentAsString(), "$.data.code");
    }

    private Account account(UserRole role, String name) {
        User user = new User(UUID.randomUUID() + "@example.com", "password", name, "010-1111-2222");
        user.selectRole(role);
        User saved = userRepository.saveAndFlush(user);
        return new Account(saved, tokenService.access(saved, UUID.randomUUID().toString()));
    }

    private Building building(User manager, String buildingName) {
        return buildingRepository.saveAndFlush(Building.builder()
                .manager(manager)
                .buildingName(buildingName)
                .roadAddress("서울 강남구 테스트로 1")
                .build());
    }

    private Room room(Building owner, String roomNo, RoomStatus status, User resident) {
        Room room = Room.builder().building(owner).roomNo(roomNo).build();
        if (status == RoomStatus.INVITED || status == RoomStatus.LIVING) {
            room.invite();
        }
        if (status == RoomStatus.LIVING) {
            room.moveIn(resident);
        }
        return roomRepository.saveAndFlush(room);
    }

    private String invitationStatus(String code) {
        return jdbc.queryForObject("SELECT status FROM Invitation_codes WHERE code = ?", String.class, code);
    }

    private Long invitationId(String code) {
        return jdbc.queryForObject("SELECT code_id FROM Invitation_codes WHERE code = ?", Long.class, code);
    }

    private long activeInvitationCount(Long roomId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM Invitation_codes WHERE room_id = ? AND status = 'ACTIVE' AND deleted_at IS NULL",
                Long.class,
                roomId);
    }

    private String roomStatus(Long roomId) {
        return jdbc.queryForObject("SELECT room_status FROM Rooms WHERE room_id = ?", String.class, roomId);
    }

    private Long roomResident(Long roomId) {
        return jdbc.queryForObject("SELECT user_id FROM Rooms WHERE room_id = ?", Long.class, roomId);
    }

    private void expire(String code) {
        jdbc.update("UPDATE Invitation_codes SET expires_at = CURRENT_TIMESTAMP WHERE code = ?", code);
    }

    private void assertUnauthorized(ResultActions action) throws Exception {
        action.andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    private record Account(User user, String token) {
        private String authorization() {
            return "Bearer " + token;
        }
    }
}
