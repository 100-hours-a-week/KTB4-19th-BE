package com.homes.zipsai.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.Socket;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.homes.zipsai.building.domain.Building;
import com.homes.zipsai.building.domain.Room;
import com.homes.zipsai.building.dto.request.ComplaintCreateRequest;
import com.homes.zipsai.building.dto.request.ComplaintStatusUpdateRequest;
import com.homes.zipsai.building.service.ComplaintService;
import com.homes.zipsai.common.domain.ComplaintNotificationContent;
import com.homes.zipsai.common.domain.Notification;
import com.homes.zipsai.common.domain.UserNotification;
import com.homes.zipsai.common.event.ComplaintNotificationEvent;
import com.homes.zipsai.common.repository.UserNotificationRepository;
import com.homes.zipsai.common.service.ComplaintNotificationListener;
import com.homes.zipsai.common.service.ComplaintNotificationStorageService;
import com.homes.zipsai.common.service.NotificationService;
import com.homes.zipsai.common.service.SseService;
import com.homes.zipsai.conversation.ai.AiComplaintState;
import com.homes.zipsai.conversation.ai.AiConverseResponse;
import com.homes.zipsai.conversation.ai.AiRoute;
import com.homes.zipsai.conversation.domain.Conversation;
import com.homes.zipsai.conversation.domain.ConversationType;
import com.homes.zipsai.user.domain.User;
import com.homes.zipsai.user.domain.UserRole;

import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:tc:mysql:8.4.11:///zipsai_notification_stream?TC_DAEMON=true",
    "spring.datasource.username=test",
    "spring.datasource.password=test",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.jpa.hibernate.naming.physical-strategy=org.hibernate.boot.model.naming.PhysicalNamingStrategyStandardImpl",
    "spring.jpa.defer-datasource-initialization=false",
    "spring.jpa.open-in-view=false",
    "spring.flyway.enabled=true",
    "app.sse.heartbeat=100ms",
    "app.sse.timeout=2s",
    "app.auth.allowed-origins[0]=http://localhost:3000"
})
@DisplayName("MySQL 커밋과 실제 HTTP SSE")
class SseIntegrationTest {

    private static final String STREAM = "/api/v1/users/me/notifications/stream";

    @LocalServerPort
    int port;

    @Autowired
    EntityManager entityManager;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    JwtEncoder jwtEncoder;

    @Autowired
    ObjectMapper json;

    @Autowired
    ComplaintNotificationStorageService storage;

    @Autowired
    ComplaintNotificationListener listener;

    @Autowired
    NotificationService notifications;

    @Autowired
    SseService sse;

    @Autowired
    ComplaintService complaints;

    @Autowired
    ApplicationEventPublisher events;

    @MockitoSpyBean
    UserNotificationRepository userNotificationRepository;

    private final HttpClient client = HttpClient.newHttpClient();
    private final List<InputStream> streams = new ArrayList<>();
    private TransactionTemplate transactions;
    private User manager;
    private User resident;
    private Building building;
    private Conversation conversation;

    @BeforeEach
    void setUp() {
        transactions = new TransactionTemplate(transactionManager);
        transactions.executeWithoutResult(transaction -> {
            manager = user(UserRole.MANAGER);
            resident = user(UserRole.RESIDENT);
            building = persist(new Building(manager, "서울시 테스트로 1", "테스트빌"));
            Room room = new Room(building, "302");
            room.invite();
            room.moveIn(resident);
            persist(room);
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
    void cleanUp() throws Exception {
        reset(userNotificationRepository);
        for (InputStream stream : streams) {
            stream.close();
        }
        streams.clear();
        transactions.executeWithoutResult(transaction -> {
            for (String entity : List.of("UserNotification", "Notification", "ComplaintDetail", "Complaint",
                    "Conversation", "Room", "Building", "User")) {
                entityManager.createQuery("delete from " + entity).executeUpdate();
            }
        });
    }

    @Test
    @DisplayName("인증 누락·잘못된 토큰·쿼리 토큰은 JSON 401로 거절한다")
    void rejectsMissingInvalidAndQueryTokens() throws Exception {
        for (HttpRequest request : List.of(request(STREAM).build(),
                request(STREAM).header("Authorization", "Bearer invalid").build(),
                request(STREAM + "?access_token=" + token(resident, Instant.now().plusSeconds(60))).build())) {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(401);
            assertThat(json.readTree(response.body()).path("error").path("code").asText()).isEqualTo("UNAUTHORIZED");
        }
    }

    @Test
    @DisplayName("현재 입주·관리 자격이 없는 사용자는 SSE를 연결하지 못한다")
    void rejectsUsersWithoutCurrentEligibility() throws Exception {
        User noRoom = transactions.execute(transaction -> user(UserRole.RESIDENT));
        User noBuilding = transactions.execute(transaction -> user(UserRole.MANAGER));
        for (User user : List.of(noRoom, noBuilding)) {
            HttpResponse<String> response = client.send(request(STREAM)
                .header("Authorization", "Bearer " + token(user, Instant.now().plusSeconds(60))).build(),
                HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(403);
            assertThat(json.readTree(response.body()).path("error").path("code").asText()).isEqualTo("FORBIDDEN");
        }
    }

    @Test
    @DisplayName("인증된 연결은 heartbeat를 보내고 설정된 timeout에 정상 종료한다")
    void sendsHeartbeatAndClosesAtConfiguredTimeout() throws Exception {
        HttpResponse<InputStream> response = connect(resident, Instant.now().plusSeconds(60), "999999999");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValue("text/event-stream");
        String body = CompletableFuture.supplyAsync(() -> readAll(response.body())).get(5, TimeUnit.SECONDS);
        assertThat(body).contains(":heartbeat")
            .doesNotContain("event:notification", "event: notification", "UNAUTHORIZED", "FORBIDDEN");
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("클라이언트가 연결을 끊어도 서버 오류를 기록하지 않고 다른 탭에는 알림을 전달한다")
    void handlesClientDisconnectWithoutServerError(CapturedOutput output) throws Exception {
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(3000);
            socket.getOutputStream().write(("GET " + STREAM + " HTTP/1.1\r\nHost: localhost\r\n"
                + "Authorization: Bearer " + token(manager, Instant.now().plusSeconds(60)) + "\r\n"
                + "Accept: text/event-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(),
                StandardCharsets.UTF_8));
            String line;
            do {
                line = reader.readLine();
                assertThat(line).isNotNull();
            } while (!line.contains(":connected"));
            socket.setSoLinger(true, 0);
        }

        HttpResponse<InputStream> active = connect(manager, Instant.now().plusSeconds(60), null);
        CompletableFuture<String> body = CompletableFuture.supplyAsync(() -> readAll(active.body()));
        listener.onComplaintNotification(createdEvent());
        assertThat(body.get(5, TimeUnit.SECONDS)).contains("event:notification", ":heartbeat");
        assertThat(output.getAll()).doesNotContain("Unhandled API failure", "\"event\":\"request_error\"",
            "Failure in @ExceptionHandler");
    }

    @Test
    @DisplayName("연결 중 Access Token이 만료돼도 설정된 SSE timeout까지 유지한다")
    void keepsConnectionUntilConfiguredTimeoutAfterAccessTokenExpiry() throws Exception {
        Instant expiresAt = Instant.now().plusSeconds(1);
        HttpResponse<InputStream> response = connect(resident, expiresAt, null);
        assertThat(response.statusCode()).isEqualTo(200);
        CompletableFuture<String> body = CompletableFuture.supplyAsync(() -> readAll(response.body()));
        assertThatThrownBy(() -> body.get(1500, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
        assertThat(body.get(5, TimeUnit.SECONDS)).contains(":heartbeat");
        assertThat(Instant.now()).isAfter(expiresAt);
    }

    @Test
    @DisplayName("새 SSE 연결 요청의 만료된 토큰은 JSON 401로 거절한다")
    void rejectsExpiredTokenOnNewConnection() throws Exception {
        HttpResponse<String> response = client.send(request(STREAM)
            .header("Authorization", "Bearer " + token(resident, Instant.now().minusSeconds(120))).build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(json.readTree(response.body()).path("error").path("code").asText()).isEqualTo("UNAUTHORIZED");
    }

    @Test
    @DisplayName("알림 수신 행이 커밋된 뒤 관리자 여러 탭에 동일 식별자를 전달한다")
    void deliversCommittedNotificationToManagersTabs() throws Exception {
        HttpResponse<InputStream> first = connect(manager, Instant.now().plusSeconds(60), null);
        HttpResponse<InputStream> second = connect(manager, Instant.now().plusSeconds(60), null);
        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(second.statusCode()).isEqualTo(200);
        CompletableFuture<String> firstEvent = notification(first.body());
        CompletableFuture<String> secondEvent = notification(second.body());

        listener.onComplaintNotification(createdEvent());
        Long id = transactions.execute(transaction -> entityManager
            .createQuery("select n from UserNotification n", UserNotification.class).getSingleResult().getId());
        for (CompletableFuture<String> event : List.of(firstEvent, secondEvent)) {
            String frame = event.get(3, TimeUnit.SECONDS);
            assertThat(frame).contains("id:" + id, "event:notification");
            String data = frame.lines().filter(line -> line.startsWith("data:")).findFirst().orElseThrow().substring(5);
            assertThat(json.readTree(data).size()).isEqualTo(2);
            assertThat(json.readTree(data).path("message").asText()).isEqualTo("success");
            assertThat(json.readTree(data).path("data").path("userNotiId").asLong()).isEqualTo(id);
        }
        transactions.executeWithoutResult(transaction -> assertThat(entityManager
            .find(UserNotification.class, id).getReadAt()).isNull());
    }

    @Test
    @DisplayName("동일 SSE 연결에서 공통 이벤트와 알림 이벤트를 함께 전달한다")
    void deliversDifferentEventTypesOverSameConnection() throws Exception {
        HttpResponse<InputStream> response = connect(manager, Instant.now().plusSeconds(60), null);
        assertThat(response.statusCode()).isEqualTo(200);
        CompletableFuture<String> body = CompletableFuture.supplyAsync(() -> readAll(response.body()));

        sse.send(manager.getId(), SseEmitter.event().name("test").data("sample"));
        listener.onComplaintNotification(createdEvent());

        assertThat(body.get(5, TimeUnit.SECONDS)).contains("event:test", "data:sample", "event:notification");
    }

    @Test
    @DisplayName("수신자가 다른 사용자에게는 알림 이벤트를 전달하지 않는다")
    void isolatesNotificationRecipients() throws Exception {
        HttpResponse<InputStream> response = connect(resident, Instant.now().plusSeconds(60), null);
        assertThat(response.statusCode()).isEqualTo(200);
        listener.onComplaintNotification(createdEvent());
        String body = CompletableFuture.supplyAsync(() -> readAll(response.body())).get(5, TimeUnit.SECONDS);
        assertThat(body).doesNotContain("event:notification", "event: notification");
    }

    @Test
    @DisplayName("오프라인 알림은 재연결 때 재전송하지 않고 DB 목록 조회로 확인한다")
    void recoversOfflineNotificationThroughListWithoutReplayingStream() throws Exception {
        listener.onComplaintNotification(createdEvent());
        HttpResponse<InputStream> response = connect(manager, Instant.now().plusSeconds(60), "999999999");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(CompletableFuture.supplyAsync(() -> readAll(response.body())).get(5, TimeUnit.SECONDS))
            .doesNotContain("event:notification");
        HttpResponse<String> list = client.send(request("/api/v1/users/me/notifications")
            .setHeader("Accept", "application/json")
            .header("Authorization", "Bearer " + token(manager, Instant.now().plusSeconds(60))).build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat(list.statusCode()).isEqualTo(200);
        assertThat(json.readTree(list.body()).path("data").path("totalCount").asLong()).isEqualTo(1);
        assertThat(json.readTree(list.body()).path("data").path("notifications").get(0).path("readAt").isNull()).isTrue();
    }

    @Test
    @DisplayName("Last-Event-ID와 Authorization을 포함한 허용 출처의 preflight를 통과한다")
    void allowsReconnectHeadersInCors() throws Exception {
        HttpResponse<String> response = client.send(request(STREAM).method("OPTIONS", HttpRequest.BodyPublishers.noBody())
            .header("Origin", "http://localhost:3000")
            .header("Access-Control-Request-Method", "GET")
            .header("Access-Control-Request-Headers", "Authorization,Last-Event-ID").build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Access-Control-Allow-Headers").orElseThrow())
            .containsIgnoringCase("Last-Event-ID");
    }

    @Test
    @DisplayName("민원 이벤트 발행 후 커밋 전에는 SSE를 보내지 않고 커밋 이후에만 보낸다")
    void waitsForComplaintTransactionCommit() throws Exception {
        HttpResponse<InputStream> response = connect(manager, Instant.now().plusSeconds(60), null);
        assertThat(response.statusCode()).isEqualTo(200);
        CompletableFuture<String> event = notification(response.body());
        transactions.executeWithoutResult(transaction -> {
            events.publishEvent(createdEvent());
            assertThatThrownBy(() -> event.get(150, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
        });
        assertThat(event.get(3, TimeUnit.SECONDS)).contains("event:notification");
    }

    @Test
    @DisplayName("민원 이벤트가 발행돼도 트랜잭션 롤백 시 알림과 SSE를 생성하지 않는다")
    void skipsRolledBackComplaintTransaction() throws Exception {
        HttpResponse<InputStream> response = connect(manager, Instant.now().plusSeconds(60), null);
        assertThat(response.statusCode()).isEqualTo(200);
        transactions.executeWithoutResult(transaction -> {
            events.publishEvent(createdEvent());
            transaction.setRollbackOnly();
        });
        assertThat(CompletableFuture.supplyAsync(() -> readAll(response.body())).get(5, TimeUnit.SECONDS))
            .doesNotContain("event:notification");
        transactions.executeWithoutResult(transaction -> assertThat(entityManager
            .createQuery("select count(n) from Notification n", Long.class).getSingleResult()).isZero());
    }

    @Test
    @DisplayName("수신 행 저장 실패 시 원본이 롤백되고 SSE를 보내지 않는다")
    void skipsDeliveryWhenRecipientStorageFails() throws Exception {
        HttpResponse<InputStream> response = connect(manager, Instant.now().plusSeconds(60), null);
        assertThat(response.statusCode()).isEqualTo(200);
        doThrow(new DataIntegrityViolationException("test recipient insert failed"))
            .when(userNotificationRepository).save(any(UserNotification.class));
        listener.onComplaintNotification(createdEvent());
        assertThat(CompletableFuture.supplyAsync(() -> readAll(response.body())).get(5, TimeUnit.SECONDS))
            .doesNotContain("event:notification");
        transactions.executeWithoutResult(transaction -> assertThat(entityManager
            .createQuery("select count(n) from Notification n", Long.class).getSingleResult()).isZero());
    }

    @Test
    @DisplayName("미읽음 스냅샷이 남아 있어도 다른 트랜잭션이 기록한 최초 읽음 시각을 반환하고 보존한다")
    void preservesCommittedReadTimeWithStaleSnapshot() {
        UserNotification received = storage.store(createdEvent());
        LocalDateTime firstReadAt = LocalDateTime.parse("2026-10-08T12:00:00.123456");
        TransactionTemplate competing = new TransactionTemplate(transactionManager);
        competing.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        transactions.executeWithoutResult(transaction -> {
            UserNotification stale = entityManager.find(UserNotification.class, received.getId());
            assertThat(stale.getReadAt()).isNull();
            competing.executeWithoutResult(other -> entityManager.createNativeQuery(
                    "UPDATE User_notifications SET read_at = :readAt WHERE user_noti_id = :id")
                .setParameter("readAt", firstReadAt).setParameter("id", received.getId()).executeUpdate());

            var response = notifications.markNotificationRead(manager.getId(), received.getId());

            assertThat(response.readAt().toLocalDateTime()).isEqualTo(firstReadAt);
            assertThat(response.unreadCount()).isZero();
        });
        transactions.executeWithoutResult(transaction -> assertThat(entityManager
            .find(UserNotification.class, received.getId()).getReadAt()).isEqualTo(firstReadAt));
    }

    @Test
    @DisplayName("알림 저장 서비스만 호출하면 DB에 저장하고 SSE는 보내지 않는다")
    void storesNotificationWithoutSendingSse() throws Exception {
        HttpResponse<InputStream> response = connect(manager, Instant.now().plusSeconds(60), null);
        assertThat(response.statusCode()).isEqualTo(200);

        storage.store(createdEvent());

        assertThat(CompletableFuture.supplyAsync(() -> readAll(response.body())).get(5, TimeUnit.SECONDS))
            .doesNotContain("event:notification");
        transactions.executeWithoutResult(transaction -> assertThat(entityManager
            .createQuery("select count(n) from UserNotification n", Long.class).getSingleResult()).isEqualTo(1));
    }

    @Test
    @DisplayName("수신 행 저장 이후 커밋이 실패하면 알림을 롤백하고 SSE를 보내지 않는다")
    void skipsDeliveryWhenNotificationCommitFails() throws Exception {
        HttpResponse<InputStream> response = connect(manager, Instant.now().plusSeconds(60), null);
        assertThat(response.statusCode()).isEqualTo(200);
        doAnswer(invocation -> {
            UserNotification received = (UserNotification) invocation.callRealMethod();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void beforeCommit(boolean readOnly) {
                    throw new DataIntegrityViolationException("test notification commit failed");
                }
            });
            return received;
        }).when(userNotificationRepository).save(any(UserNotification.class));

        listener.onComplaintNotification(createdEvent());

        assertThat(CompletableFuture.supplyAsync(() -> readAll(response.body())).get(5, TimeUnit.SECONDS))
            .doesNotContain("event:notification");
        transactions.executeWithoutResult(transaction -> {
            assertThat(entityManager.createQuery("select count(n) from Notification n", Long.class)
                .getSingleResult()).isZero();
            assertThat(entityManager.createQuery("select count(n) from UserNotification n", Long.class)
                .getSingleResult()).isZero();
        });
    }

    @Test
    @DisplayName("연결 중 퇴거하면 다음 heartbeat에서 연결을 종료한다")
    void closesStreamAfterResidentMovesOut() throws Exception {
        HttpResponse<InputStream> response = connect(resident, Instant.now().plusSeconds(60), null);
        assertThat(response.statusCode()).isEqualTo(200);
        transactions.executeWithoutResult(transaction -> entityManager
            .createQuery("select r from Room r where r.resident.id = :id", Room.class)
            .setParameter("id", resident.getId()).getSingleResult().moveOutResident());
        CompletableFuture<String> body = CompletableFuture.supplyAsync(() -> readAll(response.body()));
        assertThat(body.get(1, TimeUnit.SECONDS)).doesNotContain("event:notification", "FORBIDDEN");
    }

    @Test
    @DisplayName("실제 민원 생성과 상태 변경은 두 트랜잭션 커밋 후 각각 수신자에게 SSE를 보낸다")
    void deliversFromActualComplaintBusinessFlow() throws Exception {
        HttpResponse<InputStream> managerStream = connect(manager, Instant.now().plusSeconds(60), null);
        HttpResponse<InputStream> residentStream = connect(resident, Instant.now().plusSeconds(60), null);
        assertThat(managerStream.statusCode()).isEqualTo(200);
        assertThat(residentStream.statusCode()).isEqualTo(200);
        CompletableFuture<String> created = notification(managerStream.body());
        CompletableFuture<String> changed = notification(residentStream.body());
        Long complaintId = complaints.createComplaint(resident.getId(),
            new ComplaintCreateRequest(conversation.getId(), null, null, null)).complaintId();
        assertThat(created.get(3, TimeUnit.SECONDS)).contains("event:notification");
        assertThat(changed).isNotDone();
        complaints.updateManagerComplaintStatus(manager.getId(), complaintId,
            new ComplaintStatusUpdateRequest("DONE"));
        assertThat(changed.get(3, TimeUnit.SECONDS)).contains("event:notification");
        transactions.executeWithoutResult(transaction -> assertThat(entityManager
            .createQuery("select count(n) from UserNotification n where n.readAt is null", Long.class)
            .getSingleResult()).isEqualTo(2));
    }

    private ComplaintNotificationEvent createdEvent() {
        return new ComplaintNotificationEvent(ComplaintNotificationContent.complaintCreated(1, building.getId(),
            "천장 누수", "302", com.homes.zipsai.global.util.TimeUtils.now()), resident.getId());
    }

    private HttpResponse<InputStream> connect(User user, Instant expiresAt, String lastEventId) throws Exception {
        HttpRequest.Builder request = request(STREAM).header("Authorization", "Bearer " + token(user, expiresAt));
        if (lastEventId != null) {
            request.header("Last-Event-ID", lastEventId);
        }
        HttpResponse<InputStream> response = client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        streams.add(response.body());
        return response;
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .timeout(Duration.ofSeconds(5)).header("Accept", "text/event-stream");
    }

    private String token(User user, Instant expiresAt) {
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer("zipsai").subject(user.getId().toString())
            .issuedAt(expiresAt.minusSeconds(60)).expiresAt(expiresAt).claim("role", user.getRole().name()).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
            .getTokenValue();
    }

    private CompletableFuture<String> notification(InputStream stream) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
                StringBuilder frame = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty()) {
                        if (frame.toString().contains("event:notification")) {
                            return frame.toString();
                        }
                        frame.setLength(0);
                    } else {
                        frame.append(line).append('\n');
                    }
                }
                return frame.toString();
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        });
    }

    private static String readAll(InputStream stream) {
        try {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private User user(UserRole role) {
        User user = new User(UUID.randomUUID() + "@example.com", "password", "테스트", null);
        user.selectRole(role);
        return persist(user);
    }

    private <T> T persist(T entity) {
        entityManager.persist(entity);
        return entity;
    }
}
