package com.homes.zipsai.building;

import static org.hamcrest.Matchers.nullValue;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
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
class ManagerComplaintStatusApiTest {

    private static final String COMPLAINTS = "/api/v1/managers/me/complaints/";

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
    void changesComplaintStatusToInProgress() throws Exception {
        ManagerBuilding owner = managerBuilding();
        Complaint complaint = complaint(owner.building(), owner.room(), "천장 누수");

        mvc.perform(patch(COMPLAINTS + complaint.getId())
                .with(manager(owner.manager().getId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"statusCode\":\"IN_PROGRESS\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.complaintId").value(complaint.getId()))
            .andExpect(jsonPath("$.data.statusCode").value("IN_PROGRESS"))
            .andExpect(jsonPath("$.data.statusLabel").value("처리중"))
            .andExpect(jsonPath("$.data.resolvedAt").value(nullValue()))
            .andExpect(jsonPath("$.data.updatedAt").isNotEmpty());

        assertThat(complaintRepository.findById(complaint.getId()).orElseThrow().getStatus())
            .isEqualTo(ComplaintStatus.IN_PROGRESS);
    }

    @Test
    void recordsResolvedAtWhenStatusBecomesDone() throws Exception {
        ManagerBuilding owner = managerBuilding();
        Complaint complaint = complaint(owner.building(), owner.room(), "엘리베이터 고장");

        mvc.perform(patch(COMPLAINTS + complaint.getId())
                .with(manager(owner.manager().getId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"statusCode\":\"DONE\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.statusCode").value("DONE"))
            .andExpect(jsonPath("$.data.statusLabel").value("처리완료"))
            .andExpect(jsonPath("$.data.resolvedAt").isNotEmpty())
            .andExpect(jsonPath("$.data.updatedAt").isNotEmpty());

        assertThat(complaintRepository.findById(complaint.getId()).orElseThrow().getStatus())
            .isEqualTo(ComplaintStatus.DONE);
        assertThat(complaintRepository.findById(complaint.getId()).orElseThrow().getResolvedAt())
            .isNotNull();
    }

    @Test
    void rejectsUnsupportedStatus() throws Exception {
        ManagerBuilding owner = managerBuilding();
        Complaint complaint = complaint(owner.building(), owner.room(), "누수");

        mvc.perform(patch(COMPLAINTS + complaint.getId())
                .with(manager(owner.manager().getId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"statusCode\":\"CLOSED\"}"))
            .andExpect(status().isUnprocessableContent())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.error.details.violations[0].field").value("statusCode"));
    }

    @Test
    void rejectsMissingStatus() throws Exception {
        ManagerBuilding owner = managerBuilding();
        Complaint complaint = complaint(owner.building(), owner.room(), "누수");

        mvc.perform(patch(COMPLAINTS + complaint.getId())
                .with(manager(owner.manager().getId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("MISSING_REQUIRED_FIELD"))
            .andExpect(jsonPath("$.error.details.violations[0].field").value("statusCode"));
    }

    @Test
    void rejectsComplaintFromAnotherManagersBuilding() throws Exception {
        ManagerBuilding owner = managerBuilding();
        ManagerBuilding other = managerBuilding();
        Complaint complaint = complaint(other.building(), other.room(), "다른 건물 민원");

        mvc.perform(patch(COMPLAINTS + complaint.getId())
                .with(manager(owner.manager().getId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"statusCode\":\"DONE\"}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void returnsComplaintNotFoundWhenComplaintDoesNotExist() throws Exception {
        ManagerBuilding owner = managerBuilding();

        mvc.perform(patch(COMPLAINTS + Long.MAX_VALUE)
                .with(manager(owner.manager().getId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"statusCode\":\"DONE\"}"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("COMPLAINT_NOT_FOUND"));
    }

    @Test
    void rejectsNonPositiveComplaintId() throws Exception {
        ManagerBuilding owner = managerBuilding();

        mvc.perform(patch(COMPLAINTS + 0)
                .with(manager(owner.manager().getId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"statusCode\":\"DONE\"}"))
            .andExpect(status().isUnprocessableContent())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.error.details.violations[0].field").value("complaintId"));
    }

    @Test
    void rejectsUnauthenticatedRequest() throws Exception {
        mvc.perform(patch(COMPLAINTS + 1)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"statusCode\":\"DONE\"}"))
            .andExpect(status().isUnauthorized());
    }

    private ManagerBuilding managerBuilding() {
        User manager = user(UserRole.MANAGER);
        Building building = buildingRepository.save(Building.builder()
            .manager(manager)
            .buildingName("테스트타워")
            .roadAddress("서울 강남구 테스트로 1")
            .build());
        Room room = roomRepository.save(Room.builder().building(building).roomNo("101").build());
        return new ManagerBuilding(manager, building, room);
    }

    private Complaint complaint(Building building, Room room, String title) {
        User resident = user(UserRole.RESIDENT);
        Conversation conversation = conversationRepository.save(Conversation.builder()
            .user(resident)
            .type(ConversationType.COMPLAINT)
            .title(title)
            .build());
        return complaintRepository.save(Complaint.builder()
            .conversation(conversation)
            .user(resident)
            .building(building)
            .title(title)
            .urgency(5)
            .roomNo(room.getRoomNo())
            .build());
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

    private record ManagerBuilding(User manager, Building building, Room room) {
    }
}
