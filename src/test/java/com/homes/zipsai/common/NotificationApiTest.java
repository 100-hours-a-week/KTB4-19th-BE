package com.homes.zipsai.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.hibernate.SessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import com.homes.zipsai.building.domain.Building;
import com.homes.zipsai.building.domain.Complaint;
import com.homes.zipsai.building.domain.ComplaintStatus;
import com.homes.zipsai.building.domain.Room;
import com.homes.zipsai.common.domain.ComplaintNotificationContent;
import com.homes.zipsai.common.domain.Notification;
import com.homes.zipsai.common.domain.UserNotification;
import com.homes.zipsai.common.service.NotificationService;
import com.homes.zipsai.conversation.domain.Conversation;
import com.homes.zipsai.conversation.domain.ConversationType;
import com.homes.zipsai.global.security.AuthPrincipal;
import com.homes.zipsai.user.domain.User;
import com.homes.zipsai.user.domain.UserRole;
import com.homes.zipsai.user.domain.UserStatus;
import com.homes.zipsai.user.repository.UserRepository;

import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:tc:mysql:8.4.11:///zipsai_notifications?TC_DAEMON=true",
    "spring.datasource.username=test",
    "spring.datasource.password=test",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.jpa.hibernate.naming.physical-strategy=org.hibernate.boot.model.naming.PhysicalNamingStrategyStandardImpl",
    "spring.jpa.defer-datasource-initialization=false",
    "spring.jpa.open-in-view=false",
    "spring.jpa.properties.hibernate.generate_statistics=true",
    "spring.flyway.enabled=true"
})
@DisplayName("현재 자격으로 조회하고 읽는 민원 알림 API")
class NotificationApiTest {

    private static final String NOTIFICATIONS = "/api/v1/users/me/notifications";
    private static final LocalDateTime OCCURRED_AT = LocalDateTime.parse("2026-10-07T18:30:00.123456");

    @Autowired
    MockMvc mvc;

    @Autowired
    EntityManager entityManager;

    @Autowired
    ObjectMapper json;

    @Autowired
    NotificationService notificationService;

    @MockitoSpyBean
    UserRepository userRepository;

    private User manager;
    private User resident;
    private Building building;
    private Room room;
    private Complaint complaint;

    @BeforeEach
    void setUp() {
        manager = user(UserRole.MANAGER);
        building = persist(new Building(manager, "서울시 테스트로 1", "테스트빌"));
        resident = user(UserRole.RESIDENT);
        room = livingRoom(resident, building, "302");
        Conversation conversation = persist(Conversation.builder().user(resident)
            .type(ConversationType.COMPLAINT).title("천장 누수").build());
        complaint = persist(Complaint.builder().user(resident).building(building).conversation(conversation)
            .title("천장 누수").roomNo("302").build());
        entityManager.flush();
    }

    @Test
    @DisplayName("목록은 JSON 본문과 페이지 정보를 반환하며 조회로 읽음 처리하지 않는다")
    void returnsSnapshotAndPageWithoutMarkingRead() throws Exception {
        UserNotification notification = statusNotification(OCCURRED_AT);

        String response = mvc.perform(get(NOTIFICATIONS).with(as(resident)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.page").value(0))
            .andExpect(jsonPath("$.data.pageSize").value(20))
            .andExpect(jsonPath("$.data.totalCount").value(1))
            .andExpect(jsonPath("$.data.hasNext").value(false))
            .andExpect(jsonPath("$.data.notifications[0].userNotiId").value(notification.getId()))
            .andExpect(jsonPath("$.data.notifications[0].notificationId").value(notification.getNotification().getId()))
            .andExpect(jsonPath("$.data.notifications[0].content.type").value("COMPLAINT_STATUS_CHANGED"))
            .andExpect(jsonPath("$.data.notifications[0].content.statusCode").value("IN_PROGRESS"))
            .andExpect(jsonPath("$.data.notifications[0].content.occurredAt")
                .value("2026-10-07T18:30:00.123456+09:00"))
            .andExpect(jsonPath("$.data.notifications[0].createdAt").exists())
            .andReturn().getResponse().getContentAsString();

        assertThat(json.readTree(response).path("data").path("notifications").get(0).has("isRead")).isFalse();
        entityManager.refresh(notification);
        assertThat(notification.getReadAt()).isNull();
    }

    @Test
    @DisplayName("본문 발생 시각과 무관하게 생성 시각 내림차순이며 같은 시각은 ID 내림차순이다")
    void ordersByCreationTimeAndNotificationIdBeforePaging() throws Exception {
        createdAt(statusNotification(OCCURRED_AT.plusDays(3)), OCCURRED_AT);
        UserNotification newest = statusNotification(OCCURRED_AT.plusDays(2));
        createdAt(newest, OCCURRED_AT.plusDays(1));
        UserNotification sameTimeLaterId = statusNotification(OCCURRED_AT.plusDays(1));
        createdAt(sameTimeLaterId, OCCURRED_AT.plusDays(1));
        createdAt(statusNotification(OCCURRED_AT), OCCURRED_AT.minusDays(1));

        mvc.perform(get(NOTIFICATIONS).param("size", "2").with(as(resident)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalCount").value(4))
            .andExpect(jsonPath("$.data.hasNext").value(true))
            .andExpect(jsonPath("$.data.notifications[0].userNotiId").value(sameTimeLaterId.getId()))
            .andExpect(jsonPath("$.data.notifications[1].userNotiId").value(newest.getId()));

        mvc.perform(get(NOTIFICATIONS).param("size", "2").param("page", "1").with(as(resident)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalCount").value(4))
            .andExpect(jsonPath("$.data.hasNext").value(false));
    }

    @Test
    @DisplayName("본문 종류가 아닌 수신자 연결로 목록과 미읽음 수를 조회한다")
    void listsAndCountsOnlyTheRecipientsNotifications() throws Exception {
        createdAt(statusNotification(OCCURRED_AT), OCCURRED_AT);
        UserNotification visible = notification(resident, ComplaintNotificationContent.complaintCreated(
            complaint.getId(), building.getId(), "천장 누수", "302", OCCURRED_AT.plusDays(1)));
        createdAt(visible, OCCURRED_AT.plusDays(1));
        User other = user(UserRole.RESIDENT);
        livingRoom(other, building, "303");
        notification(other, ComplaintNotificationContent.complaintStatusChanged(
            complaint.getId(), building.getId(), "천장 누수", ComplaintStatus.DONE, OCCURRED_AT.plusDays(2)));

        mvc.perform(get(NOTIFICATIONS).param("size", "1").with(as(resident)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalCount").value(2))
            .andExpect(jsonPath("$.data.hasNext").value(true))
            .andExpect(jsonPath("$.data.notifications[0].userNotiId").value(visible.getId()));
        mvc.perform(get(NOTIFICATIONS + "/unread-count").with(as(resident)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.unreadCount").value(2));
    }

    @Test
    @DisplayName("개별 읽음은 미읽음 수를 갱신하고 반복 요청에도 최초 읽음 시각을 보존한다")
    void marksReadAndPreservesFirstReadTime() throws Exception {
        UserNotification notification = statusNotification(OCCURRED_AT);
        statusNotification(OCCURRED_AT.plusSeconds(1));
        String response = mvc.perform(patch(NOTIFICATIONS + "/" + notification.getId()).with(as(resident))
                .contentType(MediaType.APPLICATION_JSON).content("{\"isRead\":true}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.userNotiId").value(notification.getId()))
            .andExpect(jsonPath("$.data.readAt").exists())
            .andExpect(jsonPath("$.data.unreadCount").value(1))
            .andReturn().getResponse().getContentAsString();
        String firstReadAt = json.readTree(response).path("data").path("readAt").asText();
        entityManager.refresh(notification);
        assertThat(notification.getReadAt()).isEqualTo(OffsetDateTime.parse(firstReadAt).toLocalDateTime());
        mvc.perform(patch(NOTIFICATIONS + "/" + notification.getId()).with(as(resident))
                .contentType(MediaType.APPLICATION_JSON).content("{\"isRead\":true}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.readAt").value(firstReadAt));

        LocalDateTime originalReadAt = OCCURRED_AT.minusDays(1);
        entityManager.createNativeQuery("UPDATE User_notifications SET read_at = :readAt WHERE user_noti_id = :id")
            .setParameter("readAt", originalReadAt).setParameter("id", notification.getId()).executeUpdate();
        entityManager.refresh(notification);
        mvc.perform(patch(NOTIFICATIONS + "/" + notification.getId()).with(as(resident))
                .contentType(MediaType.APPLICATION_JSON).content("{\"isRead\":true}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.readAt").value("2026-10-06T18:30:00.123456+09:00"))
            .andExpect(jsonPath("$.data.unreadCount").value(1));
        entityManager.refresh(notification);
        assertThat(notification.getReadAt()).isEqualTo(originalReadAt);
    }

    @Test
    @DisplayName("다른 사용자는 수신 알림을 읽을 수 없다")
    void rejectsReadingAnotherUsersNotification() throws Exception {
        UserNotification notification = statusNotification(OCCURRED_AT);
        User other = user(UserRole.RESIDENT);
        livingRoom(other, building, "303");

        mvc.perform(patch(NOTIFICATIONS + "/" + notification.getId()).with(as(other))
                .contentType(MediaType.APPLICATION_JSON).content("{\"isRead\":true}"))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("NOTIFICATION_NOT_FOUND"));
        entityManager.refresh(notification);
        assertThat(notification.getReadAt()).isNull();
    }

    @Test
    @DisplayName("퇴거하면 조회와 읽음을 막고 재입주하면 과거 수신 알림을 다시 조회한다")
    void hidesAfterMoveOutAndRestoresAfterMoveIn() throws Exception {
        UserNotification notification = statusNotification(OCCURRED_AT);
        room.moveOutResident();
        entityManager.flush();

        mvc.perform(get(NOTIFICATIONS).with(as(resident))).andExpect(status().isForbidden());
        mvc.perform(get(NOTIFICATIONS + "/unread-count").with(as(resident))).andExpect(status().isForbidden());
        mvc.perform(patch(NOTIFICATIONS + "/" + notification.getId()).with(as(resident))
                .contentType(MediaType.APPLICATION_JSON).content("{\"isRead\":true}"))
            .andExpect(status().isForbidden());

        room.invite();
        room.moveIn(resident);
        entityManager.flush();
        mvc.perform(get(NOTIFICATIONS).with(as(resident)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalCount").value(1));
        entityManager.refresh(notification);
        assertThat(notification.getReadAt()).isNull();
    }

    @Test
    @DisplayName("다른 건물에 재입주해도 기존 민원 상세와 같은 입주 자격 기준을 적용한다")
    void allowsOriginalAuthorsPastNotificationWhenLivingInAnotherBuilding() throws Exception {
        statusNotification(OCCURRED_AT);
        room.moveOutResident();
        Building otherBuilding = persist(new Building(user(UserRole.MANAGER), "서울시 다른로 1", "다른빌"));
        entityManager.flush();
        livingRoom(resident, otherBuilding, "101");

        mvc.perform(get(NOTIFICATIONS).with(as(resident)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalCount").value(1));
    }

    @Test
    @DisplayName("이전 JWT 역할이 남아 있어도 현재 DB 역할이나 사용자 상태가 유효하지 않으면 막는다")
    void rejectsStaleRoleAndInactiveUser() throws Exception {
        statusNotification(OCCURRED_AT);
        RequestPostProcessor oldAuthentication = as(resident);
        resident.selectRole(UserRole.NONE);
        entityManager.flush();
        mvc.perform(get(NOTIFICATIONS).with(oldAuthentication)).andExpect(status().isForbidden());

        resident.selectRole(UserRole.RESIDENT);
        ReflectionTestUtils.setField(resident, "status", UserStatus.INACTIVE);
        entityManager.flush();
        mvc.perform(get(NOTIFICATIONS).with(oldAuthentication)).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("관리권을 잃으면 과거 알림도 차단하고 새 관리자에게 수신 알림을 이관하지 않는다")
    void filtersByCurrentManagementWithoutTransferringOldNotifications() throws Exception {
        notification(manager, ComplaintNotificationContent.complaintCreated(
            complaint.getId(), building.getId(), "천장 누수", "302", OCCURRED_AT));
        User newManager = user(UserRole.MANAGER);
        ReflectionTestUtils.setField(building, "manager", newManager);
        entityManager.flush();

        mvc.perform(get(NOTIFICATIONS).with(as(manager))).andExpect(status().isForbidden());
        mvc.perform(get(NOTIFICATIONS).with(as(newManager)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalCount").value(0));
        ReflectionTestUtils.setField(building, "manager", manager);
        entityManager.flush();
        mvc.perform(get(NOTIFICATIONS).with(as(manager)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalCount").value(1));
    }

    @Test
    @DisplayName("목록 크기만큼 추가 조회하지 않고 본문과 페이지 count를 한 번씩 조회한다")
    void loadsPageWithoutPerNotificationQueries() throws Exception {
        for (int i = 0; i < 23; i++) {
            statusNotification(OCCURRED_AT.plusSeconds(i));
        }
        entityManager.flush();
        var statistics = entityManager.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        assertThat(statistics.isStatisticsEnabled()).isTrue();
        statistics.clear();

        mvc.perform(get(NOTIFICATIONS).param("size", "10").with(as(resident)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalCount").value(23))
            .andExpect(jsonPath("$.data.notifications.length()").value(10));

        assertThat(statistics.getPrepareStatementCount()).isLessThanOrEqualTo(4);
    }

    @Test
    @DisplayName("인증 누락과 페이지 범위 및 읽음 값 오류를 공통 오류 계약으로 반환한다")
    void validatesAuthenticationPaginationAndReadValue() throws Exception {
        mvc.perform(get(NOTIFICATIONS)).andExpect(status().isUnauthorized());
        mvc.perform(get(NOTIFICATIONS).param("page", "-1").with(as(resident)))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_QUERY_PARAMETER"));
        mvc.perform(get(NOTIFICATIONS).param("size", "101").with(as(resident)))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_QUERY_PARAMETER"));
        mvc.perform(patch(NOTIFICATIONS + "/1").with(as(resident))
                .contentType(MediaType.APPLICATION_JSON).content("{\"isRead\":false}"))
            .andExpect(status().isUnprocessableContent());
    }

    @Test
    @DisplayName("본문에 민원 연결 정보가 없어도 수신한 JSON을 조회하고 읽을 수 있다")
    void readsReceivedJsonWithoutComplaintFields() throws Exception {
        statusNotification(OCCURRED_AT);
        UserNotification notification = persist(new UserNotification(resident,
            persist(new Notification("{\"message\":\"저장한 알림\"}"))));
        entityManager.flush();
        createdAt(notification, OCCURRED_AT.plusYears(1));

        mvc.perform(get(NOTIFICATIONS).with(as(resident)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalCount").value(2))
            .andExpect(jsonPath("$.data.notifications[0].content.message").value("저장한 알림"));
        mvc.perform(get(NOTIFICATIONS + "/unread-count").with(as(resident)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.unreadCount").value(2));
        mvc.perform(patch(NOTIFICATIONS + "/" + notification.getId()).with(as(resident))
                .contentType(MediaType.APPLICATION_JSON).content("{\"isRead\":true}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.unreadCount").value(1));
    }

    @Test
    @DisplayName("삭제된 수신 연결이나 알림 원본은 조회와 count에서 제외하고 읽지 못한다")
    void excludesDeletedNotificationsAndRecipientLinks() throws Exception {
        statusNotification(OCCURRED_AT);
        UserNotification deletedLink = statusNotification(OCCURRED_AT);
        UserNotification deletedNotification = statusNotification(OCCURRED_AT);
        ReflectionTestUtils.setField(deletedLink, "deletedAt", OCCURRED_AT);
        ReflectionTestUtils.setField(deletedNotification.getNotification(), "deletedAt", OCCURRED_AT);
        entityManager.flush();

        mvc.perform(get(NOTIFICATIONS).with(as(resident)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalCount").value(1));
        mvc.perform(get(NOTIFICATIONS + "/unread-count").with(as(resident)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.unreadCount").value(1));
        for (UserNotification notification : List.of(deletedLink, deletedNotification)) {
            mvc.perform(patch(NOTIFICATIONS + "/" + notification.getId()).with(as(resident))
                    .contentType(MediaType.APPLICATION_JSON).content("{\"isRead\":true}"))
                .andExpect(status().isNotFound());
            entityManager.refresh(notification);
            assertThat(notification.getReadAt()).isNull();
        }
    }

    @Test
    @DisplayName("삭제된 사용자는 수신 알림이 있어도 조회하거나 읽을 수 없다")
    void rejectsDeletedUser() throws Exception {
        UserNotification notification = statusNotification(OCCURRED_AT);
        ReflectionTestUtils.setField(resident, "deletedAt", OCCURRED_AT);
        entityManager.flush();

        mvc.perform(get(NOTIFICATIONS).with(as(resident))).andExpect(status().isForbidden());
        mvc.perform(get(NOTIFICATIONS + "/unread-count").with(as(resident))).andExpect(status().isForbidden());
        mvc.perform(patch(NOTIFICATIONS + "/" + notification.getId()).with(as(resident))
                .contentType(MediaType.APPLICATION_JSON).content("{\"isRead\":true}"))
            .andExpect(status().isForbidden());
    }

    private void createdAt(UserNotification notification, LocalDateTime time) {
        entityManager.createNativeQuery("UPDATE User_notifications SET created_at = :time WHERE user_noti_id = :id")
            .setParameter("time", time).setParameter("id", notification.getId()).executeUpdate();
        entityManager.refresh(notification);
    }

    private UserNotification statusNotification(LocalDateTime occurredAt) {
        return notification(resident, ComplaintNotificationContent.complaintStatusChanged(
            complaint.getId(), building.getId(), "천장 누수", ComplaintStatus.IN_PROGRESS, occurredAt));
    }

    private UserNotification notification(User recipient, ComplaintNotificationContent content) {
        Notification notification = persist(new Notification(json.writeValueAsString(content)));
        UserNotification userNotification = persist(new UserNotification(recipient, notification));
        entityManager.flush();
        return userNotification;
    }

    private User user(UserRole role) {
        User user = new User(UUID.randomUUID() + "@example.com", "password", "테스트", null);
        user.selectRole(role);
        return persist(user);
    }

    private Room livingRoom(User owner, Building ownerBuilding, String roomNo) {
        Room livingRoom = new Room(ownerBuilding, roomNo);
        livingRoom.invite();
        livingRoom.moveIn(owner);
        return persist(livingRoom);
    }

    private <T> T persist(T entity) {
        entityManager.persist(entity);
        return entity;
    }

    private RequestPostProcessor as(User user) {
        return authentication(new UsernamePasswordAuthenticationToken(new AuthPrincipal(user.getId()), null,
            List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()))));
    }
}
