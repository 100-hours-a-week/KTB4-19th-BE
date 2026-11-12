package com.homes.zipsai.building;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

import com.homes.zipsai.building.domain.Building;
import com.homes.zipsai.building.domain.BuildingCoordinateRetryJob;
import com.homes.zipsai.building.domain.BuildingCoordinateRetryJobStatus;
import com.homes.zipsai.building.repository.BuildingCoordinateRetryJobRepository;
import com.homes.zipsai.building.repository.BuildingRepository;
import com.homes.zipsai.global.security.AuthPrincipal;
import com.homes.zipsai.user.domain.User;
import com.homes.zipsai.user.domain.UserRole;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:tc:mysql:8.4.11:///zipsai_coordinate_retry_registration?TC_DAEMON=true",
    "spring.datasource.username=test",
    "spring.datasource.password=test",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.jpa.hibernate.naming.physical-strategy=org.hibernate.boot.model.naming.PhysicalNamingStrategyStandardImpl",
    "spring.jpa.defer-datasource-initialization=false",
    "spring.flyway.enabled=true",
    "app.kakao.api-key=test-kakao-key"
})
class BuildingCoordinateRetryRegistrationApiTest {

    private static final String ADDRESS = "서울 강남구 테헤란로 123";
    private static final String DOCUMENT = "{\"address_type\":\"ROAD_ADDR\",\"x\":\"127.0276\",\"y\":\"37.4979\"}";
    private static final ExecutorService HTTP_EXECUTOR_SERVICE = Executors.newVirtualThreadPerTaskExecutor();
    private static final HttpServer HTTP_SERVER = server();
    private static volatile int responseStatus;
    private static volatile String responseBody;
    private static volatile CountDownLatch requestsEntered;
    private static volatile CountDownLatch releaseRequests;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    EntityManager entityManager;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Autowired
    BuildingRepository buildingRepository;

    @Autowired
    BuildingCoordinateRetryJobRepository buildingCoordinateRetryJobRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    private User manager;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.kakao.base-url", () -> "http://127.0.0.1:" + HTTP_SERVER.getAddress().getPort());
    }

    @AfterAll
    static void stopServer() {
        HTTP_SERVER.stop(0);
        HTTP_EXECUTOR_SERVICE.shutdownNow();
    }

    @BeforeEach
    void setUp() {
        responseStatus = 200;
        responseBody = response(1, DOCUMENT);
        requestsEntered = null;
        releaseRequests = null;
        manager = transactionTemplate.execute(transaction -> {
            User user = new User(UUID.randomUUID() + "@example.com", "password", "관리자", null);
            user.selectRole(UserRole.MANAGER);
            entityManager.persist(user);
            return user;
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROAD_ADDR", "REGION_ADDR"})
    @DisplayName("최초 조회 성공은 건물과 좌표만 저장하고 재시도 작업을 생성하지 않는다")
    void savesCoordinatesWithoutRetryJob(String addressType) throws Exception {
        responseBody = response(1, DOCUMENT.replace("ROAD_ADDR", addressType));

        register().andExpect(status().isCreated());

        assertThat(buildingRepository.existsByManager_Id(manager.getId())).isTrue();

        assertThat(locationCount()).isEqualTo(1);

        assertThat(jobCount()).isZero();
    }

    @ParameterizedTest
    @MethodSource("invalidAddresses")
    @DisplayName("정상 응답의 주소 없음 복수 결과 건물 주소 아님은 422이며 건물과 작업을 저장하지 않는다")
    void rejectsInvalidAddressWithoutSaving(String body) throws Exception {
        responseBody = body;

        register().andExpect(status().isUnprocessableEntity())

            .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))

            .andExpect(jsonPath("$.error.details.violations[0].field").value("roadAddress"))

            .andExpect(jsonPath("$.error.details.violations[0].reason").isNotEmpty())

            .andExpect(jsonPath("$.data").value((Object) null));

        assertNothingSaved();
    }

    @ParameterizedTest
    @MethodSource("retryableErrors")
    @DisplayName("최초 API 장애나 응답 형식 이상은 건물과 횟수 0의 대기 작업을 원자적으로 저장한다")
    void savesPendingJobForInitialFailure(int httpStatus, String body, String errorType) throws Exception {
        responseStatus = httpStatus;
        responseBody = body;
        LocalDateTime before = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);

        register().andExpect(status().isCreated());

        LocalDateTime after = LocalDateTime.now();
        BuildingCoordinateRetryJob job = buildingCoordinateRetryJobRepository.findById(building().getId())
            .orElseThrow();
        assertThat(job.getStatus()).isEqualTo(BuildingCoordinateRetryJobStatus.PENDING);

        assertThat(job.getRetryFailureCount()).isZero();

        assertThat(job.getClaimVersion()).isZero();

        assertThat(job.getNextRetryAt()).isBetween(before.plusHours(1), after.plusHours(1));

        assertThat(job.getLastErrorType()).isEqualTo(errorType);

        assertThat(job.getLeaseExpiresAt()).isNull();

        assertThat(job.getFailureReason()).isNull();

        assertThat(jobCount()).isEqualTo(1);

        assertThat(locationCount()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"code\":-99999}"})
    @DisplayName("미분류 HTTP 오류는 건물을 유지하며 재시도 횟수 소모 없이 최종 실패 작업으로 저장한다")
    void savesFailedJobForUnknownError(String body) throws Exception {
        responseStatus = 400;
        responseBody = body;

        register().andExpect(status().isCreated());

        BuildingCoordinateRetryJob job = buildingCoordinateRetryJobRepository.findById(building().getId())
            .orElseThrow();
        assertThat(job.getStatus()).isEqualTo(BuildingCoordinateRetryJobStatus.FAILED);

        assertThat(job.getLastErrorType()).isEqualTo("UNKNOWN_HTTP_ERROR");

        assertThat(job.getFailureReason()).isEqualTo("UNKNOWN_HTTP_ERROR");

        assertThat(job.getRetryFailureCount()).isZero();

        assertThat(job.getClaimVersion()).isZero();

        assertThat(job.getNextRetryAt()).isNull();

        assertThat(job.getLeaseExpiresAt()).isNull();

        assertThat(locationCount()).isZero();
    }

    @Test
    @DisplayName("실제 MySQL에서 작업 INSERT가 실패하면 건물도 함께 롤백한다")
    void rollsBackBuildingWhenJobInsertFails() throws Exception {
        responseStatus = 503;
        long lastBuildingId = jdbcTemplate.queryForObject(
            "SELECT COALESCE(MAX(building_id), 0) FROM Buildings", Long.class);
        jdbcTemplate.execute("ALTER TABLE Building_coordinate_retry_jobs ADD CONSTRAINT reject_retry_job "
            + "CHECK (building_id <= " + lastBuildingId + ")");
        try {
            register().andExpect(status().isInternalServerError());

            assertNothingSaved();
        } finally {
            jdbcTemplate.execute("ALTER TABLE Building_coordinate_retry_jobs DROP CHECK reject_retry_job");
        }
    }

    @Test
    @DisplayName("실제 MySQL에서 최초 좌표 INSERT가 실패하면 건물도 롤백하고 재시도 작업은 만들지 않는다")
    void rollsBackBuildingWhenLocationInsertFails() throws Exception {
        long lastBuildingId = jdbcTemplate.queryForObject(
            "SELECT COALESCE(MAX(building_id), 0) FROM Buildings", Long.class);
        jdbcTemplate.execute("ALTER TABLE Building_locations ADD CONSTRAINT reject_initial_location "
            + "CHECK (building_id <= " + lastBuildingId + ")");
        try {
            register().andExpect(status().isInternalServerError());

            assertNothingSaved();
        } finally {
            jdbcTemplate.execute("ALTER TABLE Building_locations DROP CHECK reject_initial_location");
        }
    }

    @Test
    @DisplayName("동시 등록 중 API 장애가 발생해도 건물과 재시도 작업은 한 쌍만 저장한다")
    void createsOneJobForConcurrentRegistration() throws Exception {
        responseStatus = 503;
        requestsEntered = new CountDownLatch(2);
        releaseRequests = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Integer> first = executor.submit(() -> register().andReturn().getResponse().getStatus());
            Future<Integer> second = executor.submit(() -> register().andReturn().getResponse().getStatus());
            try {
                assertThat(requestsEntered.await(10, TimeUnit.SECONDS)).isTrue();
            } finally {
                releaseRequests.countDown();
            }

            assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder(201, 409);
        }

        assertThat(buildingRepository.existsByManager_Id(manager.getId())).isTrue();

        assertThat(jobCount()).isEqualTo(1);

        assertThat(locationCount()).isZero();
    }

    private static Stream<Arguments> invalidAddresses() {
        return Stream.of(
            Arguments.of(response(0, "")),
            Arguments.of(response(2, DOCUMENT + "," + DOCUMENT)),
            Arguments.of(response(2, DOCUMENT)),
            Arguments.of(response(1, DOCUMENT.replace("ROAD_ADDR", "ROAD"))),
            Arguments.of(response(1, DOCUMENT.replace("ROAD_ADDR", "REGION"))));
    }

    private static Stream<Arguments> retryableErrors() {
        return Stream.of(
            Arguments.of(503, "{}", "KAKAO_SERVER_ERROR"),
            Arguments.of(400, "{\"code\":-603}", "NETWORK_ERROR"),
            Arguments.of(401, "{}", "AUTHENTICATION_ERROR"),
            Arguments.of(403, "{}", "PERMISSION_ERROR"),
            Arguments.of(400, "{\"code\":-4}", "APP_STATE_ERROR"),
            Arguments.of(429, "{}", "QUOTA_EXCEEDED"),
            Arguments.of(400, "{\"code\":-2}", "REQUEST_CONFIGURATION_ERROR"),
            Arguments.of(200, "{\"meta\":{},\"documents\":[]}", "INVALID_RESPONSE"),
            Arguments.of(200, "{broken", "INVALID_RESPONSE"),
            Arguments.of(200, response(1, "{\"address_type\":\"ROAD_ADDR\"}"), "INVALID_RESPONSE"));
    }

    private static String response(int count, String documents) {
        return "{\"meta\":{\"total_count\":" + count + "},\"documents\":[" + documents + "]}";
    }

    private ResultActions register() throws Exception {
        return mockMvc.perform(post("/api/v1/managers/me/building").with(authentication(
                new UsernamePasswordAuthenticationToken(new AuthPrincipal(manager.getId()), null,
                    List.of(new SimpleGrantedAuthority("ROLE_MANAGER")))))
            .contentType("application/json").content("{\"roadAddress\":\"" + ADDRESS + "\"}"));
    }

    private Building building() {
        return buildingRepository.findByManager_IdAndDeletedAtIsNull(manager.getId()).orElseThrow();
    }

    private long jobCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM Building_coordinate_retry_jobs j "
            + "JOIN Buildings b ON b.building_id = j.building_id WHERE b.user_id = ?", Long.class, manager.getId());
    }

    private long locationCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM Building_locations l "
            + "JOIN Buildings b ON b.building_id = l.building_id WHERE b.user_id = ?", Long.class, manager.getId());
    }

    private void assertNothingSaved() {
        assertThat(buildingRepository.existsByManager_Id(manager.getId())).isFalse();

        assertThat(jobCount()).isZero();

        assertThat(locationCount()).isZero();
    }

    private static HttpServer server() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(HTTP_EXECUTOR_SERVICE);
            server.createContext("/v2/local/search/address.json", BuildingCoordinateRetryRegistrationApiTest::respond);
            server.start();
            return server;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static void respond(HttpExchange exchange) throws IOException {
        try (exchange) {
            CountDownLatch entered = requestsEntered;
            if (entered != null) {
                entered.countDown();
                try {
                    if (!releaseRequests.await(10, TimeUnit.SECONDS)) {
                        throw new IOException("Timed out waiting for concurrent requests");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IOException(exception);
                }
            }
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseStatus, body.length);
            exchange.getResponseBody().write(body);
        }
    }
}
