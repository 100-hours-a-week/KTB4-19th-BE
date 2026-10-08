package com.homes.zipsai.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.homes.zipsai.building.domain.Building;
import com.homes.zipsai.building.domain.Complaint;
import com.homes.zipsai.building.domain.ComplaintStatus;
import com.homes.zipsai.building.domain.Room;
import com.homes.zipsai.building.dto.request.ComplaintCreateRequest;
import com.homes.zipsai.building.dto.request.ComplaintStatusUpdateRequest;
import com.homes.zipsai.building.repository.ComplaintRepository;
import com.homes.zipsai.building.service.ComplaintService;
import com.homes.zipsai.common.domain.ComplaintNotificationContent;
import com.homes.zipsai.common.domain.UserNotification;
import com.homes.zipsai.common.repository.NotificationRepository;
import com.homes.zipsai.common.repository.UserNotificationRepository;
import com.homes.zipsai.conversation.ai.AiComplaintState;
import com.homes.zipsai.conversation.ai.AiConverseResponse;
import com.homes.zipsai.conversation.ai.AiRoute;
import com.homes.zipsai.conversation.domain.Conversation;
import com.homes.zipsai.conversation.domain.ConversationStatus;
import com.homes.zipsai.conversation.domain.ConversationType;
import com.homes.zipsai.global.security.AuthPrincipal;
import com.homes.zipsai.user.domain.User;
import com.homes.zipsai.user.domain.UserRole;
import com.homes.zipsai.user.domain.UserStatus;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:tc:mysql:8.4.11:///zipsai_notification_generation?TC_DAEMON=true",
    "spring.datasource.username=test",
    "spring.datasource.password=test",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.jpa.hibernate.naming.physical-strategy=org.hibernate.boot.model.naming.PhysicalNamingStrategyStandardImpl",
    "spring.jpa.defer-datasource-initialization=false",
    "spring.jpa.open-in-view=false",
    "spring.flyway.enabled=true"
})
@DisplayName("민원 커밋 후 알림과 수신자를 저장한다")
class ComplaintNotificationIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    EntityManager entityManager;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    ComplaintService complaintService;

    @Autowired
    ComplaintRepository complaintRepository;

    @Autowired
    NotificationRepository notificationRepository;

    @MockitoSpyBean
    UserNotificationRepository userNotificationRepository;

    @MockitoSpyBean
    ObjectMapper json;

    private TransactionTemplate transactions;
    private User manager;
    private User resident;
    private Building building;
    private Room room;
    private Conversation conversation;

    @BeforeEach
    void setUp() {
        transactions = new TransactionTemplate(transactionManager);
        transactions.executeWithoutResult(transaction -> {
            manager = user(UserRole.MANAGER);
            resident = user(UserRole.RESIDENT);
            building = persist(new Building(manager, "서울시 테스트로 1", "테스트빌"));
            room = livingRoom(resident, building);
            conversation = persist(Conversation.builder().user(resident)
                .type(ConversationType.INQUIRY).title("천장 누수").build());
            conversation.applyAiResponse(new AiConverseResponse("ai_response_success", "test-turn",
                new AiConverseResponse.Data(AiRoute.COMPLAINT, AiComplaintState.READY_TO_CONFIRM, "접수할까요?",
                    AiConverseResponse.Result.builder()
                        .complaintDraft(AiConverseResponse.DraftPatch.builder().location("거실").symptom("천장 누수").build())
                        .build())));
        });
    }

    @AfterEach
    void cleanUp() {
        reset(userNotificationRepository, json);
        // This class owns a separate Testcontainers database; committed fixtures need explicit cleanup.
        transactions.executeWithoutResult(transaction -> {
            for (String entity : List.of("UserNotification", "Notification", "ComplaintDetail", "Complaint",
                    "Conversation", "Room", "Building", "User")) {
                entityManager.createQuery("delete from " + entity).executeUpdate();
            }
        });
    }

    @Test
    @DisplayName("민원과 대화가 커밋된 뒤 관리자에게 JSON 알림을 저장한다")
    void savesCreatedNotificationAfterBusinessCommit() {
        transactions.executeWithoutResult(transaction -> {
            createComplaint();
            assertNoNotifications();
        });

        assertRecipient(manager.getId());
        JsonNode content = savedContent();
        Complaint complaint = complaintRepository.findAll().getFirst();
        assertThat(content.path("type").asText()).isEqualTo("COMPLAINT_CREATED");
        assertThat(content.path("complaintId").asLong()).isEqualTo(complaint.getId());
        assertThat(content.path("buildingId").asLong()).isEqualTo(building.getId());
        assertThat(content.path("title").asText()).isEqualTo("천장 누수");
        assertThat(content.path("roomNo").asText()).isEqualTo("302");
        assertThat(content.has("statusCode")).isFalse();
        assertThat(OffsetDateTime.parse(content.path("occurredAt").asText()).getOffset())
            .isEqualTo(ZoneOffset.ofHours(9));
        assertConversationCreated();
    }

    @Test
    @DisplayName("민원 롤백 시 알림을 저장하지 않고 대화 상태도 유지한다")
    void savesNothingWhenCreationRollsBack() {
        transactions.executeWithoutResult(transaction -> {
            createComplaint();
            transaction.setRollbackOnly();
        });

        assertNoNotifications();
        assertThat(complaintRepository.count()).isZero();
        transactions.executeWithoutResult(transaction -> assertThat(
            entityManager.find(Conversation.class, conversation.getId()).getStatus())
            .isEqualTo(ConversationStatus.ACTIVE));
    }

    @Test
    @DisplayName("알림 저장 시점에 건물에 연결된 관리자에게 전달한다")
    void selectsManagerAtNotificationStorageTime() {
        long[] newManagerId = new long[1];
        transactions.executeWithoutResult(transaction -> {
            createComplaint();
            User newManager = user(UserRole.MANAGER);
            newManagerId[0] = newManager.getId();
            ReflectionTestUtils.setField(entityManager.find(Building.class, building.getId()), "manager", newManager);
        });

        assertRecipient(newManagerId[0]);
    }

    @Test
    @DisplayName("이벤트 발행 후 민원 값이 바뀌어도 생성 시점의 제목을 저장한다")
    void storesImmutableCreationSnapshot() {
        transactions.executeWithoutResult(transaction -> {
            long complaintId = createComplaint();
            ReflectionTestUtils.setField(entityManager.find(Complaint.class, complaintId), "title", "수정된 제목");
        });

        assertThat(savedContent().path("title").asText()).isEqualTo("천장 누수");
        assertThat(complaintRepository.findAll().getFirst().getTitle()).isEqualTo("수정된 제목");
    }

    @Test
    @DisplayName("동일 상태 요청은 알림을 만들거나 완료 시각을 다시 기록하지 않는다")
    void skipsUnchangedStatusAndPreservesResolvedAt() {
        Complaint complaint = savedComplaint();
        changeStatus(complaint.getId(), ComplaintStatus.PENDING);
        assertNoNotifications();

        changeStatus(complaint.getId(), ComplaintStatus.DONE);
        assertRecipient(resident.getId());
        LocalDateTime firstResolvedAt = complaintRepository.findById(complaint.getId()).orElseThrow().getResolvedAt();
        LocalDateTime firstUpdatedAt = complaintRepository.findById(complaint.getId()).orElseThrow().getUpdatedAt();
        changeStatus(complaint.getId(), ComplaintStatus.DONE);

        assertThat(notificationRepository.count()).isEqualTo(1);
        Complaint repeated = complaintRepository.findById(complaint.getId()).orElseThrow();
        assertThat(repeated.getResolvedAt()).isEqualTo(firstResolvedAt);
        assertThat(repeated.getUpdatedAt()).isEqualTo(firstUpdatedAt);
    }

    @Test
    @DisplayName("기존 완료 상태를 재요청하면 저장된 최초 완료 시각을 보존한다")
    void preservesCompletedComplaintOnUnchangedRequest() {
        Complaint complaint = savedComplaint();
        LocalDateTime resolvedAt = LocalDateTime.parse("2026-10-07T15:00:00.123456");
        transactions.executeWithoutResult(transaction -> {
            Complaint completed = entityManager.find(Complaint.class, complaint.getId());
            completed.changeStatus(ComplaintStatus.DONE);
            ReflectionTestUtils.setField(completed, "resolvedAt", resolvedAt);
        });

        changeStatus(complaint.getId(), ComplaintStatus.DONE);

        assertThat(complaintRepository.findById(complaint.getId()).orElseThrow().getResolvedAt())
            .isEqualTo(resolvedAt);
        assertNoNotifications();
    }

    @Test
    @DisplayName("실제 상태 변경이 커밋되면 작성 입주민에게 상태 스냅샷을 저장한다")
    void savesStatusNotificationAfterCommit() {
        Complaint complaint = savedComplaint();
        transactions.executeWithoutResult(transaction -> {
            changeStatus(complaint.getId(), ComplaintStatus.IN_PROGRESS);
            assertNoNotifications();
        });

        assertRecipient(resident.getId());
        JsonNode content = savedContent();
        assertThat(content.path("type").asText()).isEqualTo("COMPLAINT_STATUS_CHANGED");
        assertThat(content.path("complaintId").asLong()).isEqualTo(complaint.getId());
        assertThat(content.path("statusCode").asText()).isEqualTo("IN_PROGRESS");
        assertThat(content.has("roomNo")).isFalse();
    }

    @Test
    @DisplayName("상태 변경이 롤백되면 민원과 알림을 변경하지 않는다")
    void savesNothingWhenStatusChangeRollsBack() {
        Complaint complaint = savedComplaint();
        transactions.executeWithoutResult(transaction -> {
            changeStatus(complaint.getId(), ComplaintStatus.DONE);
            transaction.setRollbackOnly();
        });

        assertNoNotifications();
        Complaint restored = complaintRepository.findById(complaint.getId()).orElseThrow();
        assertThat(restored.getStatus()).isEqualTo(ComplaintStatus.PENDING);
        assertThat(restored.getResolvedAt()).isNull();
    }

    @Test
    @DisplayName("알림 저장 시점에 퇴거한 작성자에게는 상태 알림을 생성하지 않는다")
    void skipsResidentWhoMovedOutBeforeStorage() {
        Complaint complaint = savedComplaint();
        transactions.executeWithoutResult(transaction -> {
            changeStatus(complaint.getId(), ComplaintStatus.IN_PROGRESS);
            entityManager.find(Room.class, room.getId()).moveOutResident();
        });

        assertNoNotifications();
        assertThat(complaintRepository.findById(complaint.getId()).orElseThrow().getStatus())
            .isEqualTo(ComplaintStatus.IN_PROGRESS);
    }

    @Test
    @DisplayName("다른 건물에 현재 입주 중인 원래 작성자에게도 상태 알림을 저장한다")
    void notifiesOriginalAuthorLivingInAnotherBuilding() {
        Complaint complaint = savedComplaint();
        transactions.executeWithoutResult(transaction -> {
            entityManager.find(Room.class, room.getId()).moveOutResident();
            livingRoom(entityManager.find(User.class, resident.getId()),
                persist(new Building(user(UserRole.MANAGER), "서울시 다른로 2", "다른빌")));
        });

        changeStatus(complaint.getId(), ComplaintStatus.IN_PROGRESS);
        assertRecipient(resident.getId());
    }

    @Test
    @DisplayName("삭제 비활성 또는 역할이 달라진 수신자에게 알림을 저장하지 않는다")
    void skipsIneligibleRecipients() {
        Complaint complaint = savedComplaint();
        transactions.executeWithoutResult(transaction -> ReflectionTestUtils.setField(
            entityManager.find(User.class, resident.getId()), "status", UserStatus.INACTIVE));
        changeStatus(complaint.getId(), ComplaintStatus.IN_PROGRESS);
        assertNoNotifications();

        transactions.executeWithoutResult(transaction -> {
            User current = entityManager.find(User.class, resident.getId());
            ReflectionTestUtils.setField(current, "status", UserStatus.ACTIVE);
            current.selectRole(UserRole.NONE);
        });
        changeStatus(complaint.getId(), ComplaintStatus.DONE);
        assertNoNotifications();

        transactions.executeWithoutResult(transaction -> {
            User current = entityManager.find(User.class, resident.getId());
            current.selectRole(UserRole.RESIDENT);
            ReflectionTestUtils.setField(current, "deletedAt", LocalDateTime.now());
        });
        changeStatus(complaint.getId(), ComplaintStatus.PENDING);
        assertNoNotifications();
    }

    @Test
    @DisplayName("삭제된 건물의 관리자에게 생성 알림을 남기지 않는다")
    void skipsDeletedBuildingBeforeStorage() {
        transactions.executeWithoutResult(transaction -> {
            createComplaint();
            ReflectionTestUtils.setField(entityManager.find(Building.class, building.getId()),
                "deletedAt", LocalDateTime.now());
        });

        assertNoNotifications();
        assertThat(complaintRepository.count()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"deleted", "inactive", "role"})
    @DisplayName("삭제 비활성 또는 역할이 달라진 관리자에게 생성 알림을 저장하지 않는다")
    void skipsIneligibleManager(String reason) {
        transactions.executeWithoutResult(transaction -> {
            User recipient = entityManager.find(User.class, manager.getId());
            switch (reason) {
                case "deleted" -> ReflectionTestUtils.setField(recipient, "deletedAt", LocalDateTime.now());
                case "inactive" -> ReflectionTestUtils.setField(recipient, "status", UserStatus.INACTIVE);
                case "role" -> recipient.selectRole(UserRole.NONE);
                default -> throw new IllegalArgumentException(reason);
            }
        });

        createComplaint();

        assertNoNotifications();
        assertThat(complaintRepository.count()).isEqualTo(1);
        assertConversationCreated();
    }

    @Test
    @DisplayName("수신 행 저장이 실패해도 민원 접수는 201이며 원본 알림도 롤백한다")
    void preservesCreatedComplaintWhenRecipientStorageFails() throws Exception {
        doAnswer(invocation -> {
            assertThat(notificationRepository.count()).isEqualTo(1);
            throw new DataIntegrityViolationException("test recipient failure");
        }).when(userNotificationRepository).save(any(UserNotification.class));

        mvc.perform(post("/api/v1/residents/me/complaints").with(as(resident))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"conversationId\":" + conversation.getId() + "}"))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.data.complaintId").exists());

        assertThat(complaintRepository.count()).isEqualTo(1);
        assertConversationCreated();
        assertNoNotifications();
        verify(userNotificationRepository).save(any(UserNotification.class));
    }

    @Test
    @DisplayName("JSON 직렬화가 실패해도 민원 상태 변경은 200이며 알림을 남기지 않는다")
    void preservesStatusChangeWhenSerializationFails() throws Exception {
        Complaint complaint = savedComplaint();
        doThrow(new IllegalStateException("test serialization failure"))
            .when(json).writeValueAsString(any(ComplaintNotificationContent.class));

        mvc.perform(patch("/api/v1/managers/me/complaints/" + complaint.getId()).with(as(manager))
                .contentType(MediaType.APPLICATION_JSON).content("{\"statusCode\":\"DONE\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.statusCode").value("DONE"));

        assertThat(complaintRepository.findById(complaint.getId()).orElseThrow().getStatus())
            .isEqualTo(ComplaintStatus.DONE);
        assertNoNotifications();
        verify(json).writeValueAsString(any(ComplaintNotificationContent.class));
    }

    private long createComplaint() {
        return complaintService.createComplaint(resident.getId(),
            new ComplaintCreateRequest(conversation.getId(), null, null, null)).complaintId();
    }

    private void changeStatus(long complaintId, ComplaintStatus status) {
        complaintService.updateManagerComplaintStatus(manager.getId(), complaintId,
            new ComplaintStatusUpdateRequest(status.name()));
    }

    private Complaint savedComplaint() {
        return transactions.execute(transaction -> persist(Complaint.builder()
            .conversation(entityManager.find(Conversation.class, conversation.getId()))
            .user(entityManager.find(User.class, resident.getId()))
            .building(entityManager.find(Building.class, building.getId()))
            .title("천장 누수").roomNo("302").build()));
    }

    private void assertRecipient(long userId) {
        assertThat(notificationRepository.count()).isEqualTo(1);
        assertThat(userNotificationRepository.count()).isEqualTo(1);
        transactions.executeWithoutResult(transaction -> {
            UserNotification received = userNotificationRepository.findAll().getFirst();
            assertThat(received.getUser().getId()).isEqualTo(userId);
            assertThat(received.getNotification().getId()).isEqualTo(notificationRepository.findAll().getFirst().getId());
            assertThat(received.getReadAt()).isNull();
        });
    }

    private JsonNode savedContent() {
        return json.readTree(notificationRepository.findAll().getFirst().getContent());
    }

    private void assertNoNotifications() {
        assertThat(notificationRepository.count()).isZero();
        assertThat(userNotificationRepository.count()).isZero();
    }

    private void assertConversationCreated() {
        transactions.executeWithoutResult(transaction -> assertThat(
            entityManager.find(Conversation.class, conversation.getId()).getStatus())
            .isEqualTo(ConversationStatus.COMPLAINT_CREATED));
    }

    private User user(UserRole role) {
        User user = new User(UUID.randomUUID() + "@example.com", "password", "테스트", null);
        user.selectRole(role);
        return persist(user);
    }

    private Room livingRoom(User user, Building owner) {
        Room room = persist(new Room(owner, "302"));
        room.invite();
        room.moveIn(user);
        return room;
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
