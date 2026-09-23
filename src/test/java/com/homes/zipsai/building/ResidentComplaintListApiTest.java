package com.homes.zipsai.building;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
import com.homes.zipsai.common.service.S3StorageService;
import com.homes.zipsai.conversation.domain.Conversation;
import com.homes.zipsai.conversation.domain.ConversationType;
import com.homes.zipsai.conversation.repository.ConversationRepository;
import com.homes.zipsai.global.security.AuthPrincipal;
import com.homes.zipsai.user.domain.User;
import com.homes.zipsai.user.domain.UserRole;
import com.homes.zipsai.user.repository.UserRepository;

@SpringBootTest(classes = ZipsaiBackendApplication.class)
@AutoConfigureMockMvc
class ResidentComplaintListApiTest {

    @MockitoBean
    S3StorageService s3StorageService;

    @BeforeEach
    void mockPresignedDownload() {
        given(s3StorageService.prepareDownload(anyString(), any())).willAnswer(invocation ->
            new S3StorageService.PresignedDownload("https://s3.test/" + invocation.getArgument(0)));
    }

    private static final String COMPLAINTS = "/api/v1/residents/me/complaints";

    @Autowired
    MockMvc mvc;

    @Autowired
    UserRepository userRepository;

    @Autowired
    BuildingRepository buildingRepository;

    @Autowired
    ComplaintRepository complaintRepository;

    @Autowired
    ComplaintDetailRepository complaintDetailRepository;

    @Autowired
    ConversationRepository conversationRepository;

    @Autowired
    RoomRepository roomRepository;

    @PersistenceContext
    EntityManager entityManager;

    @Test
    @Transactional
    void returnsOnlyOwnComplaintsWithNumericIdsAndSummaryFields() throws Exception {
        ResidentRoom residentRoom = residentRoom("302");
        complaint(residentRoom, "공용 현관 조명", ComplaintStatus.PENDING, 1);
        File attachment = File.builder()
            .fileKey("light.jpg")
            .fileSize(1024)
            .fileType("jpg")
            .originalName("light.jpg")
            .build();
        entityManager.persist(attachment);
        complaint(residentRoom, "천장 누수", ComplaintStatus.IN_PROGRESS, 9, attachment);

        ResidentRoom otherResident = residentRoom("101");
        complaint(otherResident, "다른 입주민 민원", ComplaintStatus.DONE, 5);

        mvc.perform(get(COMPLAINTS).with(resident(residentRoom.resident().getId())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalCount").value(2))
            .andExpect(jsonPath("$.data.page").value(0))
            .andExpect(jsonPath("$.data.pageSize").value(20))
            .andExpect(jsonPath("$.data.hasNext").value(false))
            .andExpect(jsonPath("$.data.complaints.length()").value(2))
            .andExpect(jsonPath("$.data.complaints[0].complaintId").isNumber())
            .andExpect(jsonPath("$.data.complaints[0].title").value("천장 누수"))
            .andExpect(jsonPath("$.data.complaints[0].statusCode").value("IN_PROGRESS"))
            .andExpect(jsonPath("$.data.complaints[0].statusLabel").value("처리중"))
            .andExpect(jsonPath("$.data.complaints[0].fileUrl").isNotEmpty())
            .andExpect(jsonPath("$.data.complaints[0].createdAt").isNotEmpty())
            .andExpect(jsonPath("$.data.complaints[0].buildingName").doesNotExist())
            .andExpect(jsonPath("$.data.complaints[0].urgency").doesNotExist());
    }

    @Test
    void filtersByTitleAndStatusAndReturnsAllStatusesWhenStatusIsMissing() throws Exception {
        ResidentRoom residentRoom = residentRoom("302");
        Complaint titleMatch = complaint(residentRoom, "천장 누수", ComplaintStatus.PENDING, 1);
        detail(titleMatch, "천장 누수 위치", "공용 현관 누수 증상");
        Complaint symptomOnly = complaint(residentRoom, "현관 전등", ComplaintStatus.IN_PROGRESS, 1);
        detail(symptomOnly, "현관", "천장 누수 증상");
        complaint(residentRoom, "보일러", ComplaintStatus.DONE, 1);

        mvc.perform(get(COMPLAINTS)
                .param("keyword", "누수")
                .with(resident(residentRoom.resident().getId())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalCount").value(1))
            .andExpect(jsonPath("$.data.complaints[0].title").value("천장 누수"));

        mvc.perform(get(COMPLAINTS)
                .param("status", "PENDING,IN_PROGRESS")
                .with(resident(residentRoom.resident().getId())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalCount").value(2));

        mvc.perform(get(COMPLAINTS).with(resident(residentRoom.resident().getId())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalCount").value(3));
    }

    @Test
    void returnsEmptyPageWhenPageExceedsLastManagerPage() throws Exception {
        ResidentRoom residentRoom = residentRoom("302");
        complaint(residentRoom, "첫 번째", ComplaintStatus.PENDING, 1);
        complaint(residentRoom, "두 번째", ComplaintStatus.PENDING, 1);

        mvc.perform(get(COMPLAINTS)
                .param("page", "2")
                .param("size", "1")
                .with(resident(residentRoom.resident().getId())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalCount").value(2))
            .andExpect(jsonPath("$.data.page").value(2))
            .andExpect(jsonPath("$.data.pageSize").value(1))
            .andExpect(jsonPath("$.data.hasNext").value(false))
            .andExpect(jsonPath("$.data.complaints.length()").value(0));
    }

    @Test
    void rejectsUnauthenticatedWrongRoleUnconnectedResidentAndInvalidQueries() throws Exception {
        mvc.perform(get(COMPLAINTS))
            .andExpect(status().isUnauthorized());

        User manager = user(UserRole.MANAGER);
        mvc.perform(get(COMPLAINTS).with(manager(manager.getId())))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        User unconnected = user(UserRole.RESIDENT);
        mvc.perform(get(COMPLAINTS).with(resident(unconnected.getId())))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        ResidentRoom residentRoom = residentRoom("302");
        mvc.perform(get(COMPLAINTS).param("page", "-1").with(resident(residentRoom.resident().getId())))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_QUERY_PARAMETER"));
        mvc.perform(get(COMPLAINTS).param("size", "101").with(resident(residentRoom.resident().getId())))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_QUERY_PARAMETER"));
        mvc.perform(get(COMPLAINTS).param("status", "UNKNOWN")
                .with(resident(residentRoom.resident().getId())))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_QUERY_PARAMETER"));
    }

    @Test
    void deniesAccessAfterResidentMovesOut() throws Exception {
        ResidentRoom residentRoom = residentRoom("302");
        complaint(residentRoom, "퇴실 이후 민원", ComplaintStatus.PENDING, 1);
        residentRoom.room().moveOutResident();
        roomRepository.save(residentRoom.room());

        mvc.perform(get(COMPLAINTS).with(resident(residentRoom.resident().getId())))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    private Complaint complaint(ResidentRoom residentRoom, String title, ComplaintStatus status, int urgency) {
        return complaint(residentRoom, title, status, urgency, null);
    }

    private Complaint complaint(
            ResidentRoom residentRoom,
            String title,
            ComplaintStatus status,
            int urgency,
            File attachment
    ) {
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
            .urgency(urgency)
            .roomNo(residentRoom.room().getRoomNo())
            .build());
        if (status != ComplaintStatus.PENDING) {
            complaint.changeStatus(status);
            complaint = complaintRepository.save(complaint);
        }
        return complaint;
    }

    private void detail(Complaint complaint, String location, String symptom) {
        complaintDetailRepository.save(ComplaintDetail.builder()
            .complaint(complaint)
            .location(location)
            .symptom(symptom)
            .aiSummary(symptom)
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
