package com.homes.zipsai.building.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import com.homes.zipsai.user.domain.User;

@SpringBootTest
@Transactional
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:tc:mysql:8.4.11:///zipsai_building_locations?TC_DAEMON=true",
    "spring.datasource.username=test",
    "spring.datasource.password=test",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.jpa.hibernate.naming.physical-strategy=org.hibernate.boot.model.naming.PhysicalNamingStrategyStandardImpl",
    "spring.jpa.defer-datasource-initialization=false",
    "spring.flyway.enabled=true"
})
class BuildingLocationPersistenceTest {

    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory(new PrecisionModel(), 4326);

    @Autowired
    EntityManager entityManager;

    @Autowired
    JdbcTemplate jdbcTemplate;

    private Building building;

    @BeforeEach
    void setUp() {
        User manager = new User("location-manager@example.com", "password", "관리자", null);
        entityManager.persist(manager);
        building = new Building(manager, "서울시 테스트로 1", "테스트빌");
        entityManager.persist(building);
        entityManager.flush();
    }

    @Test
    @DisplayName("위치 테이블은 필수 POINT와 WGS 84 좌표계를 사용한다")
    void createsRequiredPointWithWgs84() {
        assertThat(jdbcTemplate.queryForMap("""
            SELECT DATA_TYPE, IS_NULLABLE FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'Building_locations' AND COLUMN_NAME = 'location'
            """))
            .containsEntry("DATA_TYPE", "point")
            .containsEntry("IS_NULLABLE", "NO");
        assertThat(jdbcTemplate.queryForObject("""
            SELECT SRS_ID FROM information_schema.ST_GEOMETRY_COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'Building_locations' AND COLUMN_NAME = 'location'
            """, Integer.class)).isEqualTo(4326);
    }

    @Test
    @DisplayName("위치 컬럼에 공간 인덱스를 생성한다")
    void createsSpatialIndex() {
        assertThat(jdbcTemplate.queryForObject("""
            SELECT INDEX_TYPE FROM information_schema.STATISTICS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'Building_locations'
                AND INDEX_NAME = 'idx_building_locations_location' AND COLUMN_NAME = 'location'
            """, String.class)).isEqualTo("SPATIAL");
    }

    @Test
    @DisplayName("건물 ID를 공유하며 Point의 경도 위도와 좌표계를 저장하고 다시 읽는다")
    void persistsPointWithSharedBuildingId() {
        BuildingLocation location = new BuildingLocation(building,
            GEOMETRY_FACTORY.createPoint(new Coordinate(127.0276, 37.4979)));
        entityManager.persist(location);
        entityManager.flush();
        entityManager.clear();

        BuildingLocation found = entityManager.find(BuildingLocation.class, building.getId());

        assertThat(found.getId()).isEqualTo(building.getId());
        assertThat(found.getBuilding().getId()).isEqualTo(building.getId());
        assertThat(found.getLocation().getX()).isEqualTo(127.0276);
        assertThat(found.getLocation().getY()).isEqualTo(37.4979);
        assertThat(found.getLocation().getSRID()).isEqualTo(4326);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT ST_Longitude(location) FROM Building_locations WHERE building_id = ?",
            Double.class, building.getId())).isEqualTo(127.0276);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT ST_Latitude(location) FROM Building_locations WHERE building_id = ?",
            Double.class, building.getId())).isEqualTo(37.4979);
    }

    @Test
    @DisplayName("좌표가 없는 건물은 위치 행 없이 저장할 수 있다")
    void keepsBuildingWithoutLocation() {
        assertThat(entityManager.find(Building.class, building.getId())).isNotNull();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM Building_locations WHERE building_id = ?", Long.class, building.getId()))
            .isZero();
    }

    @Test
    @DisplayName("건물당 위치는 하나만 저장한다")
    void rejectsDuplicateBuildingLocation() {
        insertLocation(building.getId());

        assertThatThrownBy(() -> insertLocation(building.getId())).isInstanceOf(DataAccessException.class)
            .rootCause().hasMessageContaining("Duplicate entry");
    }

    @Test
    @DisplayName("위치 행에는 NULL 좌표를 저장할 수 없다")
    void rejectsNullLocation() {
        assertThatThrownBy(() -> jdbcTemplate.update(
            "INSERT INTO Building_locations (building_id, location) VALUES (?, NULL)", building.getId()))
            .isInstanceOf(DataAccessException.class)
            .rootCause().hasMessageContaining("Column 'location' cannot be null");
    }

    @Test
    @DisplayName("WGS 84가 아닌 좌표계는 저장할 수 없다")
    void rejectsOtherSpatialReferenceSystem() {
        assertThatThrownBy(() -> jdbcTemplate.update("""
            INSERT INTO Building_locations (building_id, location) VALUES (?, ST_GeomFromText('POINT(127 37)', 0))
            """, building.getId())).isInstanceOf(DataAccessException.class)
            .rootCause().hasMessageContaining("SRID");
    }

    @Test
    @DisplayName("존재하지 않는 건물의 위치는 저장할 수 없다")
    void rejectsMissingBuilding() {
        assertThatThrownBy(() -> insertLocation(Long.MAX_VALUE)).isInstanceOf(DataAccessException.class)
            .rootCause().hasMessageContaining("foreign key constraint fails");
    }

    private void insertLocation(long buildingId) {
        jdbcTemplate.update("""
            INSERT INTO Building_locations (building_id, location)
            VALUES (?, ST_GeomFromText('POINT(127.0276 37.4979)', 4326, 'axis-order=long-lat'))
            """, buildingId);
    }
}
