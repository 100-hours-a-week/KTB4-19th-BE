package com.homes.zipsai.user;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.homes.zipsai.ZipsaiBackendApplication;
import com.homes.zipsai.auth.service.TokenService;
import com.homes.zipsai.building.domain.Building;
import com.homes.zipsai.building.domain.Room;
import com.homes.zipsai.building.repository.BuildingRepository;
import com.homes.zipsai.building.repository.RoomRepository;
import com.homes.zipsai.user.domain.User;
import com.homes.zipsai.user.domain.UserRole;
import com.homes.zipsai.user.repository.UserRepository;

@SpringBootTest(classes = ZipsaiBackendApplication.class)
@AutoConfigureMockMvc
@Transactional
@DisplayName("관리자·입주민 마이페이지 API")
class UserMyPageApiTest {

    private static final String MANAGER_ME = "/api/v1/managers/me";
    private static final String RESIDENT_ME = "/api/v1/residents/me";

    @Autowired
    MockMvc mvc;

    @Autowired
    TokenService tokenService;

    @Autowired
    UserRepository userRepository;

    @Autowired
    BuildingRepository buildingRepository;

    @Autowired
    RoomRepository roomRepository;

    private User manager;
    private User resident;
    private Building building;
    private Room room;

    @BeforeEach
    void setUpCommonFixture() {
        manager = user(UserRole.MANAGER, "김관리", "manager@example.com", "010-1111-2222");
        building = building(manager, "A타워", "서울 강남구 테스트로 1");
        resident = user(UserRole.RESIDENT, "이입주", "resident@example.com", "010-7777-8888");
        room = livingRoom(building, resident, "302");
    }

    @Test
    @DisplayName("관리자 마이페이지는 인증 사용자 ID로 관리자와 건물 정보를 정확히 조회한다")
    void returnsManagerAndBuildingDataForAuthenticatedUser() throws Exception {
        mvc.perform(get(MANAGER_ME).header("Authorization", bearer(manager)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value(manager.getId()))
                .andExpect(jsonPath("$.data.userName").value("김관리"))
                .andExpect(jsonPath("$.data.email").value("manager@example.com"))
                .andExpect(jsonPath("$.data.phone").value("010-1111-2222"))
                .andExpect(jsonPath("$.data.buildingId").value(building.getId()))
                .andExpect(jsonPath("$.data.buildingName").value("A타워"))
                .andExpect(jsonPath("$.data.roadAddress").value("서울 강남구 테스트로 1"));
    }

    @Test
    @DisplayName("건물이 없는 관리자 마이페이지는 건물 필드를 null로 반환한다")
    void returnsNullBuildingFieldsWhenManagerHasNoBuilding() throws Exception {
        User managerWithoutBuilding = user(
                UserRole.MANAGER, "무건물관리자", "manager-no-building@example.com", "010-3333-4444");

        mvc.perform(get(MANAGER_ME).header("Authorization", bearer(managerWithoutBuilding)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.buildingId").value((Object) null))
                .andExpect(jsonPath("$.data.buildingName").value((Object) null))
                .andExpect(jsonPath("$.data.roadAddress").value((Object) null))
                .andExpect(jsonPath("$.data.userId").value(managerWithoutBuilding.getId()))
                .andExpect(jsonPath("$.data.userName").value("무건물관리자"))
                .andExpect(jsonPath("$.data.email").value("manager-no-building@example.com"))
                .andExpect(jsonPath("$.data.phone").value("010-3333-4444"));
    }

    @Test
    @DisplayName("입주민 마이페이지는 호실·건물·관리자 연결 정보를 반환한다")
    void returnsResidentRoomBuildingAndManagerData() throws Exception {
        mvc.perform(get(RESIDENT_ME).header("Authorization", bearer(resident)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value(resident.getId()))
                .andExpect(jsonPath("$.data.userName").value("이입주"))
                .andExpect(jsonPath("$.data.email").value("resident@example.com"))
                .andExpect(jsonPath("$.data.phone").value("010-7777-8888"))
                .andExpect(jsonPath("$.data.roomId").value(room.getId()))
                .andExpect(jsonPath("$.data.buildingName").value("A타워"))
                .andExpect(jsonPath("$.data.roomNo").value("302"))
                .andExpect(jsonPath("$.data.managerName").value("김관리"))
                .andExpect(jsonPath("$.data.managerPhone").value("010-1111-2222"))
                .andExpect(jsonPath("$.data.unreadCount").doesNotExist());
    }

    @Test
    @DisplayName("LIVING 호실에 연결되지 않은 입주민 마이페이지는 403을 반환한다")
    void rejectsResidentWithoutLivingRoom() throws Exception {
        User resident = user(UserRole.RESIDENT, "미연결입주민", "resident-unconnected@example.com", "010-1212-3434");

        mvc.perform(get(RESIDENT_ME).header("Authorization", bearer(resident)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("두 마이페이지 조회 API는 무인증 요청에 401을 반환한다")
    void rejectsUnauthenticatedMyPageRequests() throws Exception {
        for (String path : new String[] {MANAGER_ME, RESIDENT_ME}) {
            mvc.perform(get(path))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
        }
    }

    @Test
    @DisplayName("두 마이페이지 조회 API는 유효하지 않은 Bearer 토큰에 401을 반환한다")
    void rejectsInvalidBearerToken() throws Exception {
        for (String path : new String[] {MANAGER_ME, RESIDENT_ME}) {
            mvc.perform(get(path).header("Authorization", "Bearer invalid.token.value"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
        }
    }

    @Test
    @DisplayName("두 마이페이지 조회 API는 반대 역할 접근에 403을 반환한다")
    void rejectsOppositeRoleAccess() throws Exception {
        mvc.perform(get(MANAGER_ME).header("Authorization", bearer(resident)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        mvc.perform(get(RESIDENT_ME).header("Authorization", bearer(manager)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    private User user(UserRole role, String name, String email, String phone) {
        User user = new User(email, "password", name, phone);
        user.selectRole(role);
        return userRepository.saveAndFlush(user);
    }

    private Building building(User manager, String name, String roadAddress) {
        return buildingRepository.saveAndFlush(Building.builder()
                .manager(manager)
                .buildingName(name)
                .roadAddress(roadAddress)
                .build());
    }

    private Room livingRoom(Building building, User resident, String roomNo) {
        Room room = roomRepository.save(Room.builder().building(building).roomNo(roomNo).build());
        room.invite();
        room.moveIn(resident);
        return roomRepository.saveAndFlush(room);
    }

    private String bearer(User user) {
        return "Bearer " + tokenService.access(user, UUID.randomUUID().toString());
    }

}
