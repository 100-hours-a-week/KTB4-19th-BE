package com.homes.zipsai.building;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
import com.homes.zipsai.common.domain.File;
import com.homes.zipsai.common.repository.FileRepository;
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
@DisplayName("관리자 민원 목록 API")
class ManagerComplaintListApiTest {

    @MockitoBean
    S3StorageService s3StorageService;

    @BeforeEach
    void mockPresignedDownload() {
        given(s3StorageService.prepareDownload(anyString(), any())).willAnswer(invocation ->
            new S3StorageService.PresignedDownload("https://s3.test/" + invocation.getArgument(0)));
    }

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

    @Autowired
    FileRepository fileRepository;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    @DisplayName("관리자는 담당 건물 민원과 페이지 정보를 조회한다")
    void returnsOnlyTheAuthenticatedManagersBuildingWithPagingFields() throws Exception {
        ManagerBuilding owner = managerBuilding();
        complaint(owner.building(), owner.firstRoom(), "누수 민원", ComplaintStatus.PENDING, 9);
        File attachment = fileRepository.save(File.builder()
            .fileKey("manager-list.jpg")
            .fileSize(1024)
            .fileType("jpg")
            .originalName("manager-list.jpg")
            .build());
        complaint(owner.building(), owner.secondRoom(), "전등 민원", ComplaintStatus.IN_PROGRESS, 5, attachment);

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
            .andExpect(jsonPath("$.data.complaints[0].fileUrl")
                .value("https://s3.test/manager-list.jpg"));
    }

    @Test
    @DisplayName("제목·상태·긴급 필터와 공백 검색어를 적용한다")
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

        mvc.perform(get(COMPLAINTS).param("status", "PENDING,IN_PROGRESS")
                .with(manager(owner.manager().getId())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalCount").value(2));

        mvc.perform(get(COMPLAINTS).param("urgentOnly", "true").with(manager(owner.manager().getId())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalCount").value(1))
            .andExpect(jsonPath("$.data.complaints[0].isUrgent").value(true));

    }

    @Test
    @DisplayName("민원 목록은 고정 정렬과 페이지 경계를 적용한다")
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

        mvc.perform(get(COMPLAINTS).param("size", "100").with(manager(owner.manager().getId())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.pageSize").value(100));
    }

    @Test
    @DisplayName("접수 시각이 같으면 민원 ID 내림차순으로 정렬한다")
    void sortsByComplaintIdDescendingWhenCreatedAtMatches() throws Exception {
        ManagerBuilding owner = managerBuilding();
        Complaint first = complaint(owner.building(), owner.firstRoom(), "먼저 생성된 민원",
            ComplaintStatus.PENDING, 1);
        Complaint second = complaint(owner.building(), owner.secondRoom(), "나중 생성된 민원",
            ComplaintStatus.PENDING, 1);
        LocalDateTime sameCreatedAt = LocalDateTime.of(2026, 9, 24, 12, 0);
        jdbc.update("UPDATE Complaints SET created_at = ? WHERE complaint_id IN (?, ?)",
                sameCreatedAt, first.getId(), second.getId());

        mvc.perform(get(COMPLAINTS).param("size", "1").with(manager(owner.manager().getId())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.complaints.length()").value(1))
            .andExpect(jsonPath("$.data.complaints[0].complaintId").value(second.getId()))
            .andExpect(jsonPath("$.data.complaints[0].title").value("나중 생성된 민원"));
    }

    @Test
    @DisplayName("무인증·입주민·잘못된 조회 조건을 거부한다")
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
        mvc.perform(get(COMPLAINTS).param("size", "0").with(manager(owner.manager().getId())))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_QUERY_PARAMETER"));
        mvc.perform(get(COMPLAINTS).param("page", "not-a-number").with(manager(owner.manager().getId())))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_QUERY_PARAMETER"));
        mvc.perform(get(COMPLAINTS).param("urgentOnly", "not-a-boolean")
                .with(manager(owner.manager().getId())))
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
        return complaint(building, room, title, status, urgency, null);
    }

    private Complaint complaint(Building building, Room room, String title, ComplaintStatus status,
                                int urgency, File attachment) {
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
            .attachment(attachment)
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
