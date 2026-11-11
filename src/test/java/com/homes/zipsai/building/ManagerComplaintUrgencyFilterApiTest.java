package com.homes.zipsai.building;

import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.homes.zipsai.ZipsaiBackendApplication;
import com.homes.zipsai.building.domain.Building;
import com.homes.zipsai.building.domain.Complaint;
import com.homes.zipsai.building.repository.BuildingRepository;
import com.homes.zipsai.building.repository.ComplaintRepository;
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
class ManagerComplaintUrgencyFilterApiTest {

    private static final String COMPLAINTS = "/api/v1/managers/me/complaints";

    @MockitoBean
    S3StorageService s3StorageService;

    @Autowired
    MockMvc mvc;

    @Autowired
    UserRepository userRepository;

    @Autowired
    BuildingRepository buildingRepository;

    @Autowired
    ConversationRepository conversationRepository;

    @Autowired
    ComplaintRepository complaintRepository;

    private Building managedBuilding;

    private User resident;

    @BeforeEach
    void setUp() {
        resident = user(UserRole.RESIDENT);
        managedBuilding = building();
        for (Integer score : new Integer[] {0, 39, 40, 79, 80, 100, null, -1, 101}) {
            complaint(managedBuilding, score);
        }
        complaint(building(), 100);
    }

    @ParameterizedTest(name = "{0} 등급의 {1}번 페이지에서 점수 {2}를 조회한다")
    @CsvSource(nullValues = "NULL", value = {
        "NORMAL, 0, 101, true",
        "NORMAL, 1, -1, true",
        "NORMAL, 2, NULL, true",
        "NORMAL, 3, 39, true",
        "NORMAL, 4, 0, false",
        "PRIORITY, 0, 79, true",
        "PRIORITY, 1, 40, false",
        "URGENT, 0, 100, true",
        "URGENT, 1, 80, false"
    })
    @DisplayName("담당 건물의 민원을 등급으로 필터링한 뒤 페이지를 나눈다")
    void filtersUrgencyLevelBeforePaging(String level, int page, Integer score, boolean hasNext) throws Exception {
        mvc.perform(get(COMPLAINTS).param("urgencyLevel", level).param("page", String.valueOf(page)).param("size", "1")
                .with(manager()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.page").value(page))
            .andExpect(jsonPath("$.data.hasNext").value(hasNext))
            .andExpect(jsonPath("$.data.complaints.length()").value(1))
            .andExpect(jsonPath("$.data.complaints[0].urgency").value(is(score)))
            .andExpect(jsonPath("$.data.complaints[0].urgencyLevel").value(level));
    }

    @Test
    @DisplayName("등급 필터를 생략하면 모든 등급과 미평가 민원을 함께 조회한다")
    void includesAllScoresWhenUrgencyLevelIsOmitted() throws Exception {
        mvc.perform(get(COMPLAINTS).with(manager()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.complaints.length()").value(9));
    }

    @ParameterizedTest(name = "긴급 전용 조회와 {0} 등급의 교집합은 {1}건이다")
    @CsvSource({"URGENT, 2", "PRIORITY, 2", "NORMAL, 5"})
    @DisplayName("기존 긴급 전용 필터와 등급 필터를 함께 지정하면 두 조건을 모두 적용한다")
    void combinesUrgentOnlyAndUrgencyLevel(String level, int count) throws Exception {
        mvc.perform(get(COMPLAINTS).param("urgencyLevel", level).param("urgentOnly", "true").with(manager()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.complaints.length()").value(count));
    }

    @Test
    @DisplayName("지원하지 않는 긴급 등급은 잘못된 조회 조건으로 거절한다")
    void rejectsUnknownUrgencyLevel() throws Exception {
        mvc.perform(get(COMPLAINTS).param("urgencyLevel", "UNKNOWN").with(manager()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_QUERY_PARAMETER"));
    }

    private void complaint(Building building, Integer score) {
        Conversation conversation = conversationRepository.save(Conversation.builder()
            .user(resident)
            .type(ConversationType.COMPLAINT)
            .title("긴급도 필터 민원")
            .build());
        complaintRepository.save(Complaint.builder()
            .conversation(conversation)
            .user(resident)
            .building(building)
            .title("긴급도 필터 민원")
            .urgency(score)
            .urgent(true)
            .roomNo("101")
            .build());
    }

    private Building building() {
        return buildingRepository.save(Building.builder()
            .manager(user(UserRole.MANAGER))
            .buildingName("긴급도 테스트 건물")
            .roadAddress("서울시 테스트로 1")
            .build());
    }

    private User user(UserRole role) {
        User user = new User(UUID.randomUUID() + "@example.com", "password", "테스트", null);
        user.selectRole(role);
        return userRepository.save(user);
    }

    private RequestPostProcessor manager() {
        return authentication(new UsernamePasswordAuthenticationToken(
            new AuthPrincipal(managedBuilding.getManager().getId(), "test-session"), null,
            List.of(new SimpleGrantedAuthority("ROLE_MANAGER"))));
    }
}
