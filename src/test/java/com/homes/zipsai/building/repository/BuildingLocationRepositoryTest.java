package com.homes.zipsai.building.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.util.ReflectionTestUtils;

import com.homes.zipsai.building.domain.Building;
import com.homes.zipsai.building.domain.BuildingLocation;
import com.homes.zipsai.building.dto.NearbyBuilding;
import com.homes.zipsai.user.domain.User;
import com.homes.zipsai.user.domain.UserRole;

@DataJpaTest(showSql = false, properties = {
    "spring.datasource.url=jdbc:tc:mysql:8.4.11:///zipsai_nearby_repositories?TC_DAEMON=true",
    "spring.datasource.username=test",
    "spring.datasource.password=test",
    "spring.test.database.replace=none",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.jpa.hibernate.naming.physical-strategy=org.hibernate.boot.model.naming.PhysicalNamingStrategyStandardImpl",
    "spring.jpa.defer-datasource-initialization=false",
    "spring.flyway.enabled=true"
})
class BuildingLocationRepositoryTest {

    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory(new PrecisionModel(), 4326);

    @Autowired
    EntityManager entityManager;

    @Autowired
    BuildingLocationRepository repository;

    private Building inside;

    @BeforeEach
    void setUp() {
        inside = locatedBuilding("범위내", 37.5, 127.5);
    }

    @Test
    @DisplayName("삭제 건물과 좌표 없는 건물 및 범위 밖 건물을 제외하고 위치 값을 조회한다")
    void projectsOnlyEligibleBuildings() {
        Building unnamed = locatedBuilding(null, 37.6, 127.6);
        Building deleted = locatedBuilding("삭제건물", 37.5, 127.5);
        ReflectionTestUtils.setField(deleted, "deletedAt", LocalDateTime.now());
        building("좌표없음");
        locatedBuilding("범위밖", 36.9, 127.5);
        entityManager.flush();
        entityManager.clear();

        var found = repository.findWithinBounds(37, 38, 127, 128);

        assertThat(found).extracting(NearbyBuilding::buildingId)
            .containsExactlyInAnyOrder(inside.getId(), unnamed.getId());

        assertThat(found).filteredOn(row -> row.buildingId().equals(inside.getId())).singleElement()
            .satisfies(row -> {
                assertThat(row.buildingName()).isEqualTo("범위내");

                assertThat(row.latitude()).isEqualTo(37.5);

                assertThat(row.longitude()).isEqualTo(127.5);
            });

        assertThat(found).filteredOn(row -> row.buildingId().equals(unnamed.getId())).singleElement()
            .satisfies(row -> assertThat(row.buildingName()).isNull());
    }

    @Test
    @DisplayName("지도 사각형의 네 변과 네 꼭짓점을 포함하고 바로 바깥의 좌표는 제외한다")
    void includesEdgesAndCornersWithoutIncludingOutsidePoints() {
        double[][] boundary = {
            {37, 127}, {37, 128}, {38, 127}, {38, 128},
            {37, 127.5}, {38, 127.5}, {37.5, 127}, {37.5, 128}
        };
        List<Long> expected = new ArrayList<>();
        expected.add(inside.getId());
        for (double[] coordinate : boundary) {
            expected.add(locatedBuilding("경계", coordinate[0], coordinate[1]).getId());
        }
        locatedBuilding("남쪽", 36.999999, 127.5);
        locatedBuilding("북쪽", 38.000001, 127.5);
        locatedBuilding("서쪽", 37.5, 126.999999);
        locatedBuilding("동쪽", 37.5, 128.000001);
        entityManager.flush();

        assertThat(repository.findWithinBounds(37, 38, 127, 128))
            .extracting(NearbyBuilding::buildingId)
            .containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    @DisplayName("범위 안의 건물을 개수 제한 없이 모두 반환한다")
    void returnsAllCandidatesWithoutTruncation() {
        for (int i = 0; i < 501; i++) {
            locatedBuilding("초과건물", 37.5, 127.5);
        }
        entityManager.flush();

        assertThat(repository.findWithinBounds(37, 38, 127, 128)).hasSize(502);
    }

    @Test
    @DisplayName("범위 안에 건물이 없으면 빈 목록을 반환한다")
    void returnsEmptyListWhenBoundsHaveNoBuildings() {
        entityManager.flush();

        assertThat(repository.findWithinBounds(35, 36, 125, 126)).isEmpty();
    }

    private Building building(String name) {
        User manager = new User(UUID.randomUUID() + "@example.com", "password", "관리자", null);
        manager.selectRole(UserRole.MANAGER);
        entityManager.persist(manager);
        Building building = new Building(manager, "서울 강남구 테스트로 1", name);
        entityManager.persist(building);
        return building;
    }

    private Building locatedBuilding(String name, double latitude, double longitude) {
        Building building = building(name);
        entityManager.persist(new BuildingLocation(building,
            GEOMETRY_FACTORY.createPoint(new Coordinate(longitude, latitude))));
        return building;
    }
}
