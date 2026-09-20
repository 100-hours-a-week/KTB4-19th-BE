package com.homes.zipsai.building;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

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
class ManagerComplaintListApiTest {

    private static final String COMPLAINTS = "/api/v1/managers/me/complaints";

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
    void returnsOnlyTheAuthenticatedManagersBuildingWithPagingFields() throws Exception {
        ManagerBuilding owner = managerBuilding();
        complaint(owner.building(), owner.firstRoom(), "누수 민원", ComplaintStatus.PENDING, 9);
        complaint(owner.building(), owner.secondRoom(), "전등 민원", ComplaintStatus.IN_PROGRESS, 5);

        ManagerBuilding other = managerBuilding();
        complaint(other.building(), other.firstRoom(), "다른 건물 민원", ComplaintStatus.PENDING, 9);

        mvc.perform(get(COMPLAINTS).with(manager(owner.manager().getId())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalCount").value(2))
            .andExpect(jsonPath("$.data.page").value(0))
            .andExpect(jsonPath("$.data.pageSize").value(20))
            .andExpect(jsonPath("$.data.hasNext").value(false))
            .andExpect(jsonPath("$.data.complaints.length()").value(2))
            .andExpect(jsonPath("$.data.complaints[0].buildingName").value("테스트타워"))
            .andExpect(jsonPath("$.data.complaints[0].roomNo").value("102"))
            .andExpect(jsonPath("$.data.complaints[0].title").value("전등 민원"))
            .andExpect(jsonPath("$.data.complaints[0].statusCode").value("IN_PROGRESS"))
            .andExpect(jsonPath("$.data.complaints[0].statusLabel").value("처리중"))
            .andExpect(jsonPath("$.data.complaints[0].urgency").value(5))
            .andExpect(jsonPath("$.data.complaints[0].isUrgent").value(false))
            .andExpect(jsonPath("$.data.complaints[0].fileUrl").value(nullValue()));
    }

    @Test
    void appliesKeywordStatusUrgencyAndBlankKeywordRules() throws Exception {
        ManagerBuilding owner = managerBuilding();
        complaint(owner.building(), owner.firstRoom(), "천장 누수", ComplaintStatus.PENDING, 9);
        complaint(owner.building(), owner.secondRoom(), "현관 전등", ComplaintStatus.IN_PROGRESS, 5);

        mvc.perform(get(COMPLAINTS).param("keyword", "  누수  ").with(manager(owner.manager().getId())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalCount").value(1))
            .andExpect(jsonPath("$.data.complaints[0].title").value("천장 누수"));

        mvc.perform(get(COMPLAINTS).param("keyword", "   ").with(manager(owner.manager().getId())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalCount").value(2));

        mvc.perform(get(COMPLAINTS).param("status", "IN_PROGRESS").with(manager(owner.manager().getId())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalCount").value(1))
            .andExpect(jsonPath("$.data.complaints[0].statusCode").value("IN_PROGRESS"));

        mvc.perform(get(COMPLAINTS).param("status", "PENDING", "IN_PROGRESS")
                .with(manager(owner.manager().getId())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalCount").value(2));

        mvc.perform(get(COMPLAINTS).param("urgentOnly", "true").with(manager(owner.manager().getId())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalCount").value(1))
            .andExpect(jsonPath("$.data.complaints[0].isUrgent").value(true));

    }

    @Test
    void usesFixedSortAndPageBoundaries() throws Exception {
        ManagerBuilding owner = managerBuilding();
        complaint(owner.building(), owner.firstRoom(), "첫 번째", ComplaintStatus.PENDING, 1);
        complaint(owner.building(), owner.secondRoom(), "두 번째", ComplaintStatus.PENDING, 1);

        mvc.perform(get(COMPLAINTS).param("page", "0").param("size", "1")
                .with(manager(owner.manager().getId())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalCount").value(2))
            .andExpect(jsonPath("$.data.page").value(0))
            .andExpect(jsonPath("$.data.pageSize").value(1))
            .andExpect(jsonPath("$.data.hasNext").value(true))
            .andExpect(jsonPath("$.data.complaints.length()").value(1))
            .andExpect(jsonPath("$.data.complaints[0].title").value("두 번째"));

        mvc.perform(get(COMPLAINTS).param("page", "1").param("size", "1")
                .with(manager(owner.manager().getId())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.hasNext").value(false))
            .andExpect(jsonPath("$.data.complaints[0].title").value("첫 번째"));
    }

    @Test
    void rejectsUnauthenticatedUnauthorizedRoleAndInvalidQueries() throws Exception {
        mvc.perform(get(COMPLAINTS))
            .andExpect(status().isUnauthorized());

        User resident = user(UserRole.RESIDENT);
        mvc.perform(get(COMPLAINTS).with(authentication(new UsernamePasswordAuthenticationToken(
                new AuthPrincipal(resident.getId(), "test-session"), null,
                List.of(new SimpleGrantedAuthority("ROLE_RESIDENT"))))))
            .andExpect(status().isForbidden());

        ManagerBuilding owner = managerBuilding();
        mvc.perform(get(COMPLAINTS).param("page", "-1").with(manager(owner.manager().getId())))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_QUERY_PARAMETER"));
        mvc.perform(get(COMPLAINTS).param("size", "101").with(manager(owner.manager().getId())))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_QUERY_PARAMETER"));
        mvc.perform(get(COMPLAINTS).param("status", "UNKNOWN").with(manager(owner.manager().getId())))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_QUERY_PARAMETER"));
    }

    private ManagerBuilding managerBuilding() {
        User manager = user(UserRole.MANAGER);
        Building building = buildingRepository.save(Building.builder()
            .manager(manager)
            .buildingName("테스트타워")
            .roadAddress("서울 강남구 테스트로 1")
            .build());
        Room firstRoom = roomRepository.save(Room.builder().building(building).roomNo("101").build());
        Room secondRoom = roomRepository.save(Room.builder().building(building).roomNo("102").build());
        return new ManagerBuilding(manager, building, firstRoom, secondRoom);
    }

    private Complaint complaint(Building building, Room room, String title, ComplaintStatus status, int urgency) {
        User resident = user(UserRole.RESIDENT);
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
            .urgency(urgency)
            .roomNo(room.getRoomNo())
            .build());
        ReflectionTestUtils.setField(complaint, "status", status);
        return complaintRepository.save(complaint);
    }

    private User user(UserRole role) {
        User user = new User(UUID.randomUUID() + "@example.com", "password", "테스트", null);
        user.selectRole(role);
        return userRepository.save(user);
    }

    private RequestPostProcessor manager(Long userId) {
        return authentication(new UsernamePasswordAuthenticationToken(
            new AuthPrincipal(userId, "test-session"), null,
            List.of(new SimpleGrantedAuthority("ROLE_MANAGER"))));
    }

    private record ManagerBuilding(User manager, Building building, Room firstRoom, Room secondRoom) {
    }
}
