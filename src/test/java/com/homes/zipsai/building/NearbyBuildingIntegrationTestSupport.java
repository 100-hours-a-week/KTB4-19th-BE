package com.homes.zipsai.building;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import com.homes.zipsai.building.client.KakaoAddressClient;
import com.homes.zipsai.building.domain.Building;
import com.homes.zipsai.building.domain.BuildingLocation;
import com.homes.zipsai.building.domain.Room;
import com.homes.zipsai.building.domain.RoomStatus;
import com.homes.zipsai.global.security.AuthPrincipal;
import com.homes.zipsai.user.domain.User;
import com.homes.zipsai.user.domain.UserRole;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:tc:mysql:8.4.11:///zipsai_nearby_buildings?TC_DAEMON=true",
    "spring.datasource.username=test",
    "spring.datasource.password=test",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.jpa.hibernate.naming.physical-strategy=org.hibernate.boot.model.naming.PhysicalNamingStrategyStandardImpl",
    "spring.jpa.defer-datasource-initialization=false",
    "spring.flyway.enabled=true",
    "spring.jpa.properties.hibernate.generate_statistics=true",
    "logging.level.org.hibernate.engine.internal.StatisticalLoggingSessionEventListener=OFF"
})
abstract class NearbyBuildingIntegrationTestSupport {

    protected static final String NEARBY = "/api/v1/managers/me/buildings/nearby";
    protected static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory(new PrecisionModel(), 4326);

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    EntityManager entityManager;

    @MockitoBean
    KakaoAddressClient kakaoAddressClient;

    protected User manager;
    protected Building ownBuilding;

    @BeforeEach
    void setUp() {
        manager = user(UserRole.MANAGER);
        ownBuilding = building(manager, "본인건물");
        location(ownBuilding, 37.5, 127.5);
        entityManager.flush();
    }

    protected JsonNode success(MockHttpServletRequestBuilder request) throws Exception {
        entityManager.flush();
        entityManager.clear();
        MvcResult result = mvc.perform(request.with(as(manager))).andExpect(status().isOk())

            .andExpect(jsonPath("$.message").doesNotExist())

            .andExpect(jsonPath("$.data.buildings").isArray()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("data");
    }

    protected JsonNode item(JsonNode data, Building building) {
        for (JsonNode item : data.path("buildings")) {
            if (item.path("buildingId").asLong() == building.getId()) {
                return item;
            }
        }
        throw new AssertionError("Expected building " + building.getId());
    }

    protected List<Long> ids(JsonNode data) {
        List<Long> result = new ArrayList<>();
        for (JsonNode item : data.path("buildings")) {
            result.add(item.path("buildingId").asLong());
        }
        return result;
    }

    protected void assertBuilding(JsonNode item, Building building, double latitude, double longitude,
            long totalCount, long livingCount, long emptyCount) {
        assertThat(item.size()).isEqualTo(5);

        assertThat(item.path("buildingId").isNumber()).isTrue();

        assertThat(item.path("buildingId").asLong()).isEqualTo(building.getId());

        if (building.getBuildingName() == null) {
            assertThat(item.path("buildingName").isNull()).isTrue();
        } else {
            assertThat(item.path("buildingName").asText()).isEqualTo(building.getBuildingName());
        }

        assertThat(item.path("latitude").isNumber()).isTrue();

        assertThat(item.path("longitude").isNumber()).isTrue();

        assertThat(item.path("latitude").asDouble()).isEqualTo(latitude);

        assertThat(item.path("longitude").asDouble()).isEqualTo(longitude);

        JsonNode summary = item.path("roomSummary");
        assertThat(summary.size()).isEqualTo(3);

        assertThat(summary.path("totalCount").isNumber()).isTrue();

        assertThat(summary.path("livingCount").isNumber()).isTrue();

        assertThat(summary.path("emptyCount").isNumber()).isTrue();

        assertThat(summary.path("totalCount").asLong()).isEqualTo(totalCount);

        assertThat(summary.path("livingCount").asLong()).isEqualTo(livingCount);

        assertThat(summary.path("emptyCount").asLong()).isEqualTo(emptyCount);

        assertThat(summary.has("invitedCount")).isFalse();
    }

    protected MockHttpServletRequestBuilder bounds() {
        return bounds(37, 38, 127, 128);
    }

    protected MockHttpServletRequestBuilder bounds(double minLat, double maxLat, double minLon, double maxLon) {
        return get(NEARBY).param("minLatitude", Double.toString(minLat)).param("maxLatitude", Double.toString(maxLat))
            .param("minLongitude", Double.toString(minLon)).param("maxLongitude", Double.toString(maxLon));
    }

    protected User user(UserRole role) {
        User user = new User(UUID.randomUUID() + "@example.com", "password", "개인정보이름", null);
        user.selectRole(role);
        entityManager.persist(user);
        return user;
    }

    protected Building building(User owner, String name) {
        Building building = new Building(owner, "서울 강남구 테스트로 1", name);
        entityManager.persist(building);
        return building;
    }

    protected Building locatedBuilding(String name, double latitude, double longitude) {
        Building building = building(user(UserRole.MANAGER), name);
        location(building, latitude, longitude);
        return building;
    }

    protected void location(Building building, double latitude, double longitude) {
        entityManager.persist(new BuildingLocation(building,
            GEOMETRY_FACTORY.createPoint(new Coordinate(longitude, latitude))));
    }

    protected Room room(Building building, String number, RoomStatus status) {
        Room room = new Room(building, number);
        if (status == RoomStatus.INVITED || status == RoomStatus.LIVING) {
            room.invite();
        }
        if (status == RoomStatus.LIVING) {
            room.moveIn(user(UserRole.RESIDENT));
        }
        entityManager.persist(room);
        return room;
    }

    protected void softDelete(Object entity) {
        ReflectionTestUtils.setField(entity, "deletedAt", LocalDateTime.now());
    }

    protected RequestPostProcessor as(User user) {
        return authentication(new UsernamePasswordAuthenticationToken(new AuthPrincipal(user.getId()), null,
            List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()))));
    }
}
