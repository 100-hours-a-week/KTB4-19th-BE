package com.homes.zipsai.building;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.homes.zipsai.building.client.KakaoAddressClient;
import com.homes.zipsai.building.domain.Building;
import com.homes.zipsai.building.repository.BuildingLocationRepository;
import com.homes.zipsai.building.repository.BuildingRepository;
import com.homes.zipsai.global.security.AuthPrincipal;
import com.homes.zipsai.user.domain.User;
import com.homes.zipsai.user.domain.UserRole;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:tc:mysql:8.4.11:///zipsai_building_registration_coordinates?TC_DAEMON=true",
    "spring.datasource.username=test",
    "spring.datasource.password=test",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.jpa.hibernate.naming.physical-strategy=org.hibernate.boot.model.naming.PhysicalNamingStrategyStandardImpl",
    "spring.jpa.defer-datasource-initialization=false",
    "spring.flyway.enabled=true"
})
class BuildingRegistrationCoordinatesApiTest {

    private static final String ADDRESS = "서울 강남구 테헤란로 123";
    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory(new PrecisionModel(), 4326);

    @Autowired
    MockMvc mvc;

    @Autowired
    EntityManager entityManager;

    @Autowired
    TransactionTemplate transactions;

    @Autowired
    BuildingRepository buildingRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @MockitoBean
    KakaoAddressClient kakaoAddressClient;

    @MockitoSpyBean
    BuildingLocationRepository buildingLocationRepository;

    private User manager;

    @BeforeEach
    void setUp() {
        manager = transactions.execute(transaction -> {
            User user = new User(UUID.randomUUID() + "@example.com", "password", "관리자", null);
            user.selectRole(UserRole.MANAGER);
            entityManager.persist(user);
            return user;
        });
        when(kakaoAddressClient.findCoordinates(anyString())).thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("기존 건물 등록 API에서 좌표를 조회하고 건물과 위치를 함께 저장한다")
    void registersBuildingWithCoordinatesOutsideTransaction() throws Exception {
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return Optional.of(point());
        }).when(kakaoAddressClient).findCoordinates(ADDRESS);

        mvc.perform(post("/api/v1/managers/me/building").with(as(manager))
                .contentType("application/json").content(requestBody()))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.buildingId").isNumber())
            .andExpect(jsonPath("$.data.buildingName").value("테스트빌"))
            .andExpect(jsonPath("$.data.roadAddress").value(ADDRESS))
            .andExpect(jsonPath("$.data.location").doesNotExist())
            .andExpect(jsonPath("$.data.latitude").doesNotExist())
            .andExpect(jsonPath("$.data.longitude").doesNotExist());

        Building building = buildingRepository.findByManager_IdAndDeletedAtIsNull(manager.getId()).orElseThrow();
        assertThat(locationCount()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT ST_Longitude(location) FROM Building_locations WHERE building_id = ?",
            Double.class, building.getId())).isEqualTo(127.0276);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT ST_Latitude(location) FROM Building_locations WHERE building_id = ?",
            Double.class, building.getId())).isEqualTo(37.4979);
        verify(kakaoAddressClient).findCoordinates(ADDRESS);
    }

    @Test
    @DisplayName("좌표 조회 결과가 없으면 건물만 저장하고 201로 응답한다")
    void registersBuildingWithoutCoordinates() throws Exception {
        register(manager).andExpect(status().isCreated());

        assertThat(buildingRepository.existsByManager_Id(manager.getId())).isTrue();
        assertThat(locationCount()).isZero();
        verify(kakaoAddressClient).findCoordinates(ADDRESS);
    }

    @Test
    @DisplayName("위치 저장의 DB 오류는 건물까지 롤백하며 좌표 조회 실패로 처리하지 않는다")
    void rollsBackBuildingWhenLocationStorageFails() throws Exception {
        when(kakaoAddressClient.findCoordinates(ADDRESS)).thenReturn(Optional.of(point()));
        doThrow(new DataIntegrityViolationException("test location storage failure"))
            .when(buildingLocationRepository).saveAndFlush(any());

        register(manager).andExpect(status().isInternalServerError());

        assertThat(buildingRepository.existsByManager_Id(manager.getId())).isFalse();
        assertThat(locationCount()).isZero();
    }

    @Test
    @DisplayName("이미 건물이 있는 관리자의 중복 등록은 좌표 조회 없이 409를 반환한다")
    void rejectsDuplicateBeforeGeocoding() throws Exception {
        transactions.executeWithoutResult(transaction -> entityManager.persist(
            new Building(manager, ADDRESS, "기존빌")));

        register(manager).andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("BUILDING_ALREADY_EXISTS"));

        verifyNoInteractions(kakaoAddressClient);
    }

    @Test
    @DisplayName("인증 권한 또는 입력 검증 실패 시 좌표 API를 호출하지 않는다")
    void rejectsUnauthorizedForbiddenAndInvalidRequestsBeforeGeocoding() throws Exception {
        mvc.perform(post("/api/v1/managers/me/building")
                .contentType("application/json").content(requestBody()))
            .andExpect(status().isUnauthorized());
        User resident = transactions.execute(transaction -> {
            User user = new User(UUID.randomUUID() + "@example.com", "password", "입주민", null);
            user.selectRole(UserRole.RESIDENT);
            entityManager.persist(user);
            return user;
        });
        register(resident).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/managers/me/building").with(as(manager))
                .contentType("application/json").content("{}"))
            .andExpect(status().isBadRequest());

        verifyNoInteractions(kakaoAddressClient);
    }

    @Test
    @DisplayName("존재하지 않는 인증 사용자는 좌표 조회 없이 401을 반환한다")
    void rejectsMissingUserBeforeGeocoding() throws Exception {
        mvc.perform(post("/api/v1/managers/me/building").with(authentication(
                new UsernamePasswordAuthenticationToken(new AuthPrincipal(Long.MAX_VALUE), null,
                    List.of(new SimpleGrantedAuthority("ROLE_MANAGER")))))
                .contentType("application/json").content(requestBody()))
            .andExpect(status().isUnauthorized());

        verifyNoInteractions(kakaoAddressClient);
    }

    @Test
    @DisplayName("동시 등록은 건물과 위치 한 쌍만 저장하고 나머지 요청은 409로 응답한다")
    void handlesConcurrentRegistrationWithOneBuildingAndLocation() throws Exception {
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            entered.countDown();
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            return Optional.of(point());
        }).when(kakaoAddressClient).findCoordinates(ADDRESS);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> register(manager).andReturn());
            var second = executor.submit(() -> register(manager).andReturn());
            try {
                assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            } finally {
                release.countDown();
            }
            MvcResult firstResult = first.get(15, TimeUnit.SECONDS);
            MvcResult secondResult = second.get(15, TimeUnit.SECONDS);
            assertThat(List.of(firstResult.getResponse().getStatus(), secondResult.getResponse().getStatus()))
                .containsExactlyInAnyOrder(201, 409);
            MvcResult conflict = firstResult.getResponse().getStatus() == 409 ? firstResult : secondResult;
            assertThat(conflict.getResponse().getContentAsString()).contains("BUILDING_ALREADY_EXISTS");
        }
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM Buildings WHERE user_id = ?", Long.class, manager.getId())).isEqualTo(1);
        assertThat(locationCount()).isEqualTo(1);
    }

    private ResultActions register(User user) throws Exception {
        return mvc.perform(post("/api/v1/managers/me/building").with(as(user))
            .contentType("application/json").content(requestBody()));
    }

    private String requestBody() {
        return """
            {"buildingName":"테스트빌","roadAddress":"%s"}
            """.formatted(ADDRESS);
    }

    private long locationCount() {
        return jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM Building_locations location
            JOIN Buildings building ON location.building_id = building.building_id WHERE building.user_id = ?
            """, Long.class, manager.getId());
    }

    private Point point() {
        return GEOMETRY_FACTORY.createPoint(new Coordinate(127.0276, 37.4979));
    }

    private RequestPostProcessor as(User user) {
        return authentication(new UsernamePasswordAuthenticationToken(new AuthPrincipal(user.getId()), null,
            List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()))));
    }
}
