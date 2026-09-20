package com.homes.zipsai.building;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.homes.zipsai.ZipsaiBackendApplication;
import com.homes.zipsai.building.domain.Building;
import com.homes.zipsai.building.domain.Complaint;
import com.homes.zipsai.building.domain.ComplaintDetail;
import com.homes.zipsai.building.domain.ComplaintStatus;
import com.homes.zipsai.building.domain.Room;
import com.homes.zipsai.building.repository.BuildingRepository;
import com.homes.zipsai.building.repository.ComplaintDetailRepository;
import com.homes.zipsai.building.repository.ComplaintRepository;
import com.homes.zipsai.building.repository.RoomRepository;
import com.homes.zipsai.common.domain.File;
import com.homes.zipsai.conversation.domain.Conversation;
import com.homes.zipsai.conversation.domain.ConversationType;
import com.homes.zipsai.conversation.repository.ConversationRepository;
import com.homes.zipsai.global.security.AuthPrincipal;
import com.homes.zipsai.user.domain.User;
import com.homes.zipsai.user.domain.UserRole;
import com.homes.zipsai.user.repository.UserRepository;

@SpringBootTest(classes = ZipsaiBackendApplication.class)
@AutoConfigureMockMvc
class ResidentComplaintDetailApiTest {

    private static final String COMPLAINTS = "/api/v1/residents/me/complaints/";

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

    @Autowired
    ComplaintDetailRepository complaintDetailRepository;

    @PersistenceContext
    EntityManager entityManager;

    @Test
    @Transactional
    void returnsOwnedComplaintDetailWithRepresentativeAttachment() throws Exception {
        ResidentRoom residentRoom = residentRoom("203호");
        File attachment = File.builder()
            .fileKey("leak.jpg")
            .fileSize(245760)
            .fileType("jpg")
            .originalName("leak.jpg")
            .build();
        entityManager.persist(attachment);
        Complaint complaint = complaint(residentRoom, "천장 누수", ComplaintStatus.IN_PROGRESS, attachment);
        complaintDetailRepository.save(ComplaintDetail.builder()
            .complaint(complaint)
            .location("203호 안방 천장")
            .occurredTime(OffsetDateTime.parse("2026-09-18T10:00:00+09:00"))
            .symptom("천장 가운데에서 물이 떨어짐")
            .aiSummary("천장에서 물이 떨어지는 민원")
            .build());

        mvc.perform(get(COMPLAINTS + complaint.getId()).with(resident(residentRoom.resident().getId())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.complaintId").isNumber())
            .andExpect(jsonPath("$.data.conversationId").isNumber())
            .andExpect(jsonPath("$.data.conversationAvailable").value(true))
            .andExpect(jsonPath("$.data.buildingName").value("테스트타워"))
            .andExpect(jsonPath("$.data.roomNo").value("203호"))
            .andExpect(jsonPath("$.data.title").value("천장 누수"))
            .andExpect(jsonPath("$.data.statusCode").value("IN_PROGRESS"))
            .andExpect(jsonPath("$.data.statusLabel").value("처리중"))
            .andExpect(jsonPath("$.data.location").value("203호 안방 천장"))
            .andExpect(jsonPath("$.data.occurredTime").value("2026-09-18T10:00:00+09:00"))
            .andExpect(jsonPath("$.data.symptom").value("천장 가운데에서 물이 떨어짐"))
            .andExpect(jsonPath("$.data.aiSummary").value("천장에서 물이 떨어지는 민원"))
            .andExpect(jsonPath("$.data.attachmentCount").value(1))
            .andExpect(jsonPath("$.data.attachments.length()").value(1))
            .andExpect(jsonPath("$.data.attachments[0].attachmentId").isNumber())
            .andExpect(jsonPath("$.data.attachments[0].fileUrl").isNotEmpty())
            .andExpect(jsonPath("$.data.attachments[0].originalName").value("leak.jpg"))
            .andExpect(jsonPath("$.data.attachments[0].fileType").value("jpg"))
            .andExpect(jsonPath("$.data.attachments[0].fileSize").value(245760))
            .andExpect(jsonPath("$.data.attachments[0].seq").value(1))
            .andExpect(jsonPath("$.data.createdAt").isNotEmpty())
            .andExpect(jsonPath("$.data.resolvedAt").value(nullValue()))
            .andExpect(jsonPath("$.data.urgency").doesNotExist())
            .andExpect(jsonPath("$.data.isUrgent").doesNotExist());
    }

    @Test
    void returnsResolvedAtForDoneComplaint() throws Exception {
        ResidentRoom residentRoom = residentRoom("204호");
        Complaint complaint = complaint(residentRoom, "엘리베이터 고장", ComplaintStatus.DONE, null);
        detail(complaint);

        mvc.perform(get(COMPLAINTS + complaint.getId()).with(resident(residentRoom.resident().getId())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.statusCode").value("DONE"))
            .andExpect(jsonPath("$.data.resolvedAt").isNotEmpty())
            .andExpect(jsonPath("$.data.attachments.length()").value(0))
            .andExpect(jsonPath("$.data.attachmentCount").value(0));
    }

    @Test
    void rejectsOtherResidentsUnknownComplaintAndInvalidIds() throws Exception {
        ResidentRoom owner = residentRoom("203호");
        Complaint complaint = complaint(owner, "천장 누수", ComplaintStatus.PENDING, null);
        detail(complaint);
        ResidentRoom other = residentRoom("205호");

        mvc.perform(get(COMPLAINTS + complaint.getId()).with(resident(other.resident().getId())))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        mvc.perform(get(COMPLAINTS + Long.MAX_VALUE).with(resident(owner.resident().getId())))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("COMPLAINT_NOT_FOUND"));
        mvc.perform(get(COMPLAINTS + "0").with(resident(owner.resident().getId())))
            .andExpect(status().isUnprocessableContent())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.error.details.violations[0].field").value("complaintId"));
        mvc.perform(get(COMPLAINTS + "not-a-number").with(resident(owner.resident().getId())))
            .andExpect(status().isUnprocessableContent())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void rejectsUnauthenticatedWrongRoleUnconnectedResidentAndResidentAfterMoveOut() throws Exception {
        mvc.perform(get(COMPLAINTS + 1))
            .andExpect(status().isUnauthorized());

        User manager = user(UserRole.MANAGER);
        mvc.perform(get(COMPLAINTS + 1).with(manager(manager.getId())))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        User unconnected = user(UserRole.RESIDENT);
        mvc.perform(get(COMPLAINTS + 1).with(resident(unconnected.getId())))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        ResidentRoom residentRoom = residentRoom("206호");
        Complaint complaint = complaint(residentRoom, "퇴실 이후 민원", ComplaintStatus.PENDING, null);
        detail(complaint);
        residentRoom.room().moveOutResident();
        roomRepository.save(residentRoom.room());

        mvc.perform(get(COMPLAINTS + complaint.getId()).with(resident(residentRoom.resident().getId())))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    private Complaint complaint(ResidentRoom residentRoom, String title, ComplaintStatus status, File attachment) {
        Conversation conversation = conversationRepository.save(Conversation.builder()
            .user(residentRoom.resident())
            .type(ConversationType.COMPLAINT)
            .title(title)
            .build());
        Complaint complaint = complaintRepository.save(Complaint.builder()
            .conversation(conversation)
            .user(residentRoom.resident())
            .building(residentRoom.building())
            .attachment(attachment)
            .title(title)
            .urgency(5)
            .roomNo(residentRoom.room().getRoomNo())
            .build());
        if (status != ComplaintStatus.PENDING) {
            complaint.changeStatus(status);
            complaint = complaintRepository.save(complaint);
        }
        return complaint;
    }

    private void detail(Complaint complaint) {
        complaintDetailRepository.save(ComplaintDetail.builder()
            .complaint(complaint)
            .location("안방")
            .symptom("물이 떨어짐")
            .aiSummary("누수 민원")
            .build());
    }

    private ResidentRoom residentRoom(String roomNo) {
        User manager = user(UserRole.MANAGER);
        Building building = buildingRepository.save(Building.builder()
            .manager(manager)
            .buildingName("테스트타워")
            .roadAddress("서울 강남구 테스트로 1")
            .build());
        User resident = user(UserRole.RESIDENT);
        Room room = Room.builder().building(building).roomNo(roomNo).build();
        room.invite();
        room.moveIn(resident);
        roomRepository.save(room);
        return new ResidentRoom(resident, building, room);
    }

    private User user(UserRole role) {
        User user = new User(UUID.randomUUID() + "@example.com", "password", "테스트", null);
        user.selectRole(role);
        return userRepository.save(user);
    }

    private RequestPostProcessor resident(Long userId) {
        return authentication(new UsernamePasswordAuthenticationToken(
            new AuthPrincipal(userId, "test-session"), null,
            List.of(new SimpleGrantedAuthority("ROLE_RESIDENT"))));
    }

    private RequestPostProcessor manager(Long userId) {
        return authentication(new UsernamePasswordAuthenticationToken(
            new AuthPrincipal(userId, "test-session"), null,
            List.of(new SimpleGrantedAuthority("ROLE_MANAGER"))));
    }

    private record ResidentRoom(User resident, Building building, Room room) {
    }
}
