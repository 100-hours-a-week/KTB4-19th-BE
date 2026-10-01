package com.homes.zipsai.building;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.homes.zipsai.ZipsaiBackendApplication;
import com.homes.zipsai.building.domain.Building;
import com.homes.zipsai.building.domain.Complaint;
import com.homes.zipsai.building.domain.ComplaintStatus;
import com.homes.zipsai.building.domain.Room;
import com.homes.zipsai.building.domain.RoomStatus;
import com.homes.zipsai.building.repository.BuildingRepository;
import com.homes.zipsai.building.repository.ComplaintRepository;
import com.homes.zipsai.building.repository.RoomRepository;
import com.homes.zipsai.conversation.domain.Conversation;
import com.homes.zipsai.conversation.domain.ConversationType;
import com.homes.zipsai.conversation.repository.ConversationRepository;
import com.homes.zipsai.global.security.AuthPrincipal;
import com.homes.zipsai.user.domain.User;
import com.homes.zipsai.user.domain.UserRole;
import com.homes.zipsai.user.repository.UserRepository;

@SpringBootTest(classes = ZipsaiBackendApplication.class)
@AutoConfigureMockMvc
@DisplayName("관리자 건물 조회 API")
class ManagerBuildingReadApiTest {

    private static final String BUILDING = "/api/v1/managers/me/building";
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @Autowired
    MockMvc mvc;

    @Autowired
    UserRepository userRepository;

    @Autowired
    BuildingRepository buildingRepository;

    @Autowired
    RoomRepository roomRepository;

    @Autowired
    ConversationRepository conversationRepository;

    @Autowired
    ComplaintRepository complaintRepository;

    @Test
    @DisplayName("관리자는 건물 상세·호실 요약·호실 목록을 조회한다")
    void returnsBuildingDetailRoomSummaryAndRoomList() throws Exception {
        ManagerBuilding owner = managerBuilding("테스트타워");
        User resident = user(UserRole.RESIDENT, "홍길동");
        room(owner.building(), "101", RoomStatus.EMPTY, null);
        room(owner.building(), "102", RoomStatus.INVITED, null);
        room(owner.building(), "103", RoomStatus.LIVING, resident);
        Room deleted = room(owner.building(), "104", RoomStatus.EMPTY, null);
        delete(deleted);

        mvc.perform(get(BUILDING)
                .with(manager(owner.manager().getId())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.buildingId").value(owner.building().getId()))
            .andExpect(jsonPath("$.data.buildingName").value("테스트타워"))
            .andExpect(jsonPath("$.data.roadAddress").value("서울 강남구 테스트로 1"))
            .andExpect(jsonPath("$.data.totalRoomCount").value(3))
            .andExpect(jsonPath("$.data.updatedAt").isNotEmpty());

        mvc.perform(get(BUILDING + "/rooms/summary")
                .with(manager(owner.manager().getId())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.livingCount").value(1))
            .andExpect(jsonPath("$.data.invitedCount").value(1))
            .andExpect(jsonPath("$.data.emptyCount").value(1))
            .andExpect(jsonPath("$.data.totalCount").value(3));

        mvc.perform(get(BUILDING + "/rooms")
                .with(manager(owner.manager().getId())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.buildingId").value(owner.building().getId()))
            .andExpect(jsonPath("$.data.buildingName").value("테스트타워"))
            .andExpect(jsonPath("$.data.totalCount").value(3))
            .andExpect(jsonPath("$.data.rooms.length()").value(3))
            .andExpect(jsonPath("$.data.rooms[?(@.roomNo == '101')].roomId").value(contains(greaterThan(0))))
            .andExpect(jsonPath("$.data.rooms[?(@.roomNo == '101')].roomStatus").value(contains("EMPTY")))
            .andExpect(jsonPath("$.data.rooms[?(@.roomNo == '101')].roomStatusLabel").value(contains("공실")))
            .andExpect(jsonPath("$.data.rooms[?(@.roomNo == '101')].residentName")
                    .value(contains(nullValue())))
            .andExpect(jsonPath("$.data.rooms[?(@.roomNo == '102')].roomStatusLabel").value(contains("초대됨")))
            .andExpect(jsonPath("$.data.rooms[?(@.roomNo == '103')].roomStatus").value(contains("LIVING")))
            .andExpect(jsonPath("$.data.rooms[?(@.roomNo == '103')].roomStatusLabel").value(contains("입주")))
            .andExpect(jsonPath("$.data.rooms[?(@.roomNo == '103')].residentName").value(contains("홍길동")))
            .andExpect(jsonPath("$.data.rooms[?(@.roomNo == '104')].roomNo").value(empty()));
    }

    @Test
    @DisplayName("호실이 없는 건물은 빈 목록과 0건 요약을 반환한다")
    void returnsNullableBuildingNameAndZeroCountsWhenBuildingHasNoRooms() throws Exception {
        ManagerBuilding owner = managerBuilding(null);

        mvc.perform(get(BUILDING)
                .with(manager(owner.manager().getId())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.buildingName").value((Object) null))
            .andExpect(jsonPath("$.data.totalRoomCount").value(0));

        mvc.perform(get(BUILDING + "/rooms/summary")
                .with(manager(owner.manager().getId())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.livingCount").value(0))
            .andExpect(jsonPath("$.data.invitedCount").value(0))
            .andExpect(jsonPath("$.data.emptyCount").value(0))
            .andExpect(jsonPath("$.data.totalCount").value(0));

        mvc.perform(get(BUILDING + "/rooms")
                .with(manager(owner.manager().getId())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.buildingName").value((Object) null))
            .andExpect(jsonPath("$.data.totalCount").value(0))
            .andExpect(jsonPath("$.data.rooms.length()").value(0));

        mvc.perform(get(BUILDING + "/complaints/summary")
                .with(manager(owner.manager().getId())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.pendingCount").value(0))
            .andExpect(jsonPath("$.data.inProgressCount").value(0))
            .andExpect(jsonPath("$.data.weeklyDoneCount").value(0))
            .andExpect(jsonPath("$.data.totalCount").value(0));
    }

    @Test
    @DisplayName("민원 요약은 담당 건물의 이번 주 완료 건수를 집계한다")
    void returnsComplaintSummaryForActiveBuildingComplaints() throws Exception {
        ManagerBuilding owner = managerBuilding("민원건물");
        User resident = user(UserRole.RESIDENT, "퇴실 입주민");
        Room occupied = room(owner.building(), "101", RoomStatus.LIVING, resident);
        LocalDateTime now = LocalDateTime.now(SEOUL);
        LocalDateTime weekStart = now.toLocalDate()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .atStartOfDay();

        complaint(owner.building(), resident, occupied.getRoomNo(), "접수 민원", ComplaintStatus.PENDING, null);
        complaint(owner.building(), resident, occupied.getRoomNo(), "처리중 민원",
                ComplaintStatus.IN_PROGRESS, null);
        complaint(owner.building(), resident, occupied.getRoomNo(), "이번 주 완료",
                ComplaintStatus.DONE, weekStart);
        complaint(owner.building(), resident, occupied.getRoomNo(), "지난 완료", ComplaintStatus.DONE,
                weekStart.minusSeconds(1));
        complaint(owner.building(), resident, occupied.getRoomNo(), "미래 완료", ComplaintStatus.DONE,
                now.plusHours(1));
        Complaint deleted = complaint(owner.building(), resident, occupied.getRoomNo(), "삭제 민원",
                ComplaintStatus.PENDING, null);
        delete(deleted);

        ManagerBuilding other = managerBuilding("다른 건물");
        complaint(other.building(), resident, "201", "타 건물 민원", ComplaintStatus.PENDING, null);
        occupied.moveOutResident();
        roomRepository.saveAndFlush(occupied);

        mvc.perform(get(BUILDING + "/complaints/summary")
                .with(manager(owner.manager().getId())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.pendingCount").value(1))
            .andExpect(jsonPath("$.data.inProgressCount").value(1))
            .andExpect(jsonPath("$.data.weeklyDoneCount").value(1))
            .andExpect(jsonPath("$.data.totalCount").value(5));
    }

    @Test
    @DisplayName("담당 건물이 없거나 삭제된 관리자 요청은 404를 반환한다")
    void rejectsManagerWithoutActiveBuilding() throws Exception {
        User manager = user(UserRole.MANAGER, "관리자");
        ManagerBuilding deleted = managerBuilding("삭제건물");
        delete(deleted.building());

        for (User user : List.of(manager, deleted.manager())) {
            for (String suffix : List.of("", "/rooms/summary", "/rooms", "/complaints/summary")) {
                mvc.perform(get(BUILDING + suffix).with(manager(user.getId())))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("BUILDING_NOT_FOUND"));
            }
        }
    }

    @Test
    @DisplayName("관리자 요청은 인증 사용자의 건물만 반환한다")
    void returnsOnlyAuthenticatedManagersBuilding() throws Exception {
        managerBuilding("타인건물");
        ManagerBuilding owner = managerBuilding("소유건물");

        mvc.perform(get(BUILDING).with(manager(owner.manager().getId())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.buildingId").value(owner.building().getId()))
            .andExpect(jsonPath("$.data.buildingName").value("소유건물"));
    }

    @Test
    @DisplayName("건물 조회는 관리자 인증을 요구하고 입주민 접근을 거부한다")
    void requiresManagerAuthentication() throws Exception {
        for (String suffix : List.of("", "/rooms/summary", "/rooms", "/complaints/summary")) {
            mvc.perform(get(BUILDING + suffix))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
        }

        User resident = user(UserRole.RESIDENT, "입주민");
        for (String suffix : List.of("", "/rooms/summary", "/rooms", "/complaints/summary")) {
            mvc.perform(get(BUILDING + suffix).with(resident(resident.getId())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        }
    }

    private ManagerBuilding managerBuilding(String buildingName) {
        User manager = user(UserRole.MANAGER, "관리자");
        Building building = buildingRepository.save(Building.builder()
            .manager(manager)
            .buildingName(buildingName)
            .roadAddress("서울 강남구 테스트로 1")
            .build());
        return new ManagerBuilding(manager, building);
    }

    private Room room(Building building, String roomNo, RoomStatus status, User resident) {
        Room room = Room.builder().building(building).roomNo(roomNo).build();
        if (status == RoomStatus.INVITED) {
            room.invite();
        } else if (status == RoomStatus.LIVING) {
            room.invite();
            room.moveIn(resident);
        }
        return roomRepository.save(room);
    }

    private void delete(Object entity) {
        ReflectionTestUtils.setField(entity, "deletedAt", LocalDateTime.now());
        if (entity instanceof Building building) {
            buildingRepository.saveAndFlush(building);
        } else if (entity instanceof Room room) {
            roomRepository.saveAndFlush(room);
        } else if (entity instanceof Complaint complaint) {
            complaintRepository.saveAndFlush(complaint);
        }
    }

    private Complaint complaint(
            Building building,
            User resident,
            String roomNo,
            String title,
            ComplaintStatus status,
            LocalDateTime resolvedAt
    ) {
        Conversation conversation = conversationRepository.save(Conversation.builder()
            .user(resident)
            .type(ConversationType.COMPLAINT)
            .title(title)
            .build());
        Complaint complaint = complaintRepository.save(Complaint.builder()
            .conversation(conversation)
            .user(resident)
            .building(building)
            .title(title)
            .urgency(1)
            .roomNo(roomNo)
            .build());
        complaint.changeStatus(status);
        if (resolvedAt != null) {
            ReflectionTestUtils.setField(complaint, "resolvedAt", resolvedAt);
        }
        return complaintRepository.saveAndFlush(complaint);
    }

    private User user(UserRole role, String name) {
        User user = new User(UUID.randomUUID() + "@example.com", "password", name, null);
        user.selectRole(role);
        return userRepository.save(user);
    }

    private RequestPostProcessor manager(Long userId) {
        return authentication(new UsernamePasswordAuthenticationToken(
            new AuthPrincipal(userId, "test-session"), null,
            List.of(new SimpleGrantedAuthority("ROLE_MANAGER"))));
    }

    private RequestPostProcessor resident(Long userId) {
        return authentication(new UsernamePasswordAuthenticationToken(
            new AuthPrincipal(userId, "test-session"), null,
            List.of(new SimpleGrantedAuthority("ROLE_RESIDENT"))));
    }

    private record ManagerBuilding(User manager, Building building) {
    }
}
