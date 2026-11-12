package com.homes.zipsai.building;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.homes.zipsai.ZipsaiBackendApplication;
import com.homes.zipsai.building.domain.Building;
import com.homes.zipsai.building.domain.Complaint;
import com.homes.zipsai.building.domain.ComplaintDetail;
import com.homes.zipsai.building.domain.Room;
import com.homes.zipsai.building.repository.BuildingRepository;
import com.homes.zipsai.building.repository.ComplaintDetailRepository;
import com.homes.zipsai.building.repository.ComplaintRepository;
import com.homes.zipsai.building.repository.RoomRepository;
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
class ManagerComplaintDetailApiTest {

    @MockitoBean
    S3StorageService s3StorageService;

    @BeforeEach
    void mockPresignedDownload() {
        given(s3StorageService.prepareDownload(anyString(), any())).willAnswer(invocation ->
            new S3StorageService.PresignedDownload("https://s3.test/" + invocation.getArgument(0)));
    }

    private static final String COMPLAINT_LIST_PATH = "/api/v1/managers/me/complaints";
    private static final String COMPLAINTS = COMPLAINT_LIST_PATH + "/";

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

    @Test
    @DisplayName("담당 건물의 민원 상세는 점수 등급과 AI 긴급 판정을 각각 반환한다")
    void returnsComplaintDetailForTheAuthenticatedManagersBuilding() throws Exception {
        ManagerBuilding owner = managerBuilding();
        Complaint complaint = complaint(owner.building(), owner.room(), "천장 누수", 9, true);
        detail(complaint);

        mvc.perform(get(COMPLAINTS + complaint.getId()).with(manager(owner.manager().getId())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.complaintId").value(complaint.getId()))
            .andExpect(jsonPath("$.data.conversationId").value(complaint.getConversation().getId()))
            .andExpect(jsonPath("$.data.conversationAvailable").value(true))
            .andExpect(jsonPath("$.data.buildingName").value("테스트타워"))
            .andExpect(jsonPath("$.data.roomNo").value("101"))
            .andExpect(jsonPath("$.data.title").value("천장 누수"))
            .andExpect(jsonPath("$.data.statusCode").value("PENDING"))
            .andExpect(jsonPath("$.data.statusLabel").value("처리전"))
            .andExpect(jsonPath("$.data.urgency").value(9))
            .andExpect(jsonPath("$.data.isUrgent").value(true))
            .andExpect(jsonPath("$.data.urgencyLevel").value("NORMAL"))
            .andExpect(jsonPath("$.data.location").value("안방 천장"))
            .andExpect(jsonPath("$.data.symptom").value("물이 떨어짐"))
            .andExpect(jsonPath("$.data.aiSummary").value("안방 천장에서 물이 떨어지는 민원"))
            .andExpect(jsonPath("$.data.attachmentCount").value(0))
            .andExpect(jsonPath("$.data.attachments.length()").value(0))
            .andExpect(jsonPath("$.data.createdAt").isNotEmpty())
            .andExpect(jsonPath("$.data.resolvedAt").value(nullValue()));
    }

    @ParameterizedTest(name = "점수 {0}, AI 판정 {1}, 예상 등급 {2}")
    @CsvSource(nullValues = "NULL", value = {
        "0, false, NORMAL",
        "39, false, NORMAL",
        "40, false, PRIORITY",
        "79, true, PRIORITY",
        "80, false, URGENT",
        "100, true, URGENT",
        "NULL, true, NORMAL",
        "-1, true, NORMAL",
        "101, true, NORMAL"
    })
    @DisplayName("관리자 목록과 상세는 점수 등급과 AI 긴급 판정을 각각 반환한다")
    void returnsSameUrgencyInListAndDetail(Integer score, Boolean aiUrgent, String level)
            throws Exception {
        ManagerBuilding owner = managerBuilding();
        Complaint complaint = complaint(owner.building(), owner.room(), "천장 누수", score, aiUrgent);
        detail(complaint);
        RequestPostProcessor principal = manager(owner.manager().getId());

        ResultActions list = mvc.perform(get(COMPLAINT_LIST_PATH).with(principal));
        ResultActions detail = mvc.perform(get(COMPLAINTS + complaint.getId()).with(principal));

        assertAll(
            () -> list.andExpectAll(
                status().isOk(),

                jsonPath("$.data.complaints[0].urgency").value(is(score)),

                jsonPath("$.data.complaints[0].urgencyLevel").value(is(level)),

                jsonPath("$.data.complaints[0].isUrgent").value(is(aiUrgent))),

            () -> detail.andExpectAll(
                status().isOk(),

                jsonPath("$.data.urgency").value(is(score)),

                jsonPath("$.data.urgencyLevel").value(is(level)),

                jsonPath("$.data.isUrgent").value(is(aiUrgent)))
        );
    }

    @Test
    void rejectsComplaintFromAnotherManagersBuilding() throws Exception {
        ManagerBuilding owner = managerBuilding();
        ManagerBuilding other = managerBuilding();
        Complaint complaint = complaint(other.building(), other.room(), "다른 건물 민원", 5, false);
        detail(complaint);

        mvc.perform(get(COMPLAINTS + complaint.getId()).with(manager(owner.manager().getId())))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void returnsComplaintNotFoundWhenComplaintDoesNotExist() throws Exception {
        ManagerBuilding owner = managerBuilding();

        mvc.perform(get(COMPLAINTS + Long.MAX_VALUE).with(manager(owner.manager().getId())))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("COMPLAINT_NOT_FOUND"));
    }

    @Test
    void rejectsNonPositiveComplaintId() throws Exception {
        ManagerBuilding owner = managerBuilding();

        mvc.perform(get(COMPLAINTS + 0).with(manager(owner.manager().getId())))
            .andExpect(status().isUnprocessableContent())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.error.details.violations[0].field").value("complaintId"));
    }

    @Test
    void rejectsUnauthenticatedRequest() throws Exception {
        mvc.perform(get(COMPLAINTS + 1))
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

    private Complaint complaint(Building building, Room room, String title, Integer urgency, Boolean urgent) {
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
            .urgency(urgency)
            .urgent(urgent)
            .roomNo(room.getRoomNo())
            .build());
    }

    private void detail(Complaint complaint) {
        complaintDetailRepository.save(ComplaintDetail.builder()
            .complaint(complaint)
            .location("안방 천장")
            .occurredTime(OffsetDateTime.parse("2026-09-18T10:00:00+09:00"))
            .symptom("물이 떨어짐")
            .aiSummary("안방 천장에서 물이 떨어지는 민원")
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
