package com.homes.zipsai.building.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;

import com.homes.zipsai.user.domain.User;

class BuildingLocationTest {

    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory(new PrecisionModel(), 4326);

    private Building building;

    @BeforeEach
    void setUp() {
        building = new Building(new User("manager@example.com", "password", "관리자", null),
            "서울시 테스트로 1", "테스트빌");
    }

    @Test
    @DisplayName("건물 위치는 경도를 X 위도를 Y로 보존한다")
    void preservesLongitudeAndLatitude() {
        BuildingLocation location = new BuildingLocation(building, point(127.0276, 37.4979));

        assertThat(location.getBuilding()).isSameAs(building);
        assertThat(location.getLocation().getX()).isEqualTo(127.0276);
        assertThat(location.getLocation().getY()).isEqualTo(37.4979);
        assertThat(location.getLocation().getSRID()).isEqualTo(4326);
    }

    @ParameterizedTest
    @CsvSource({"180,90", "-179.999999,-90", "0,0"})
    @DisplayName("WGS 84 범위 안의 경계 좌표를 허용한다")
    void acceptsBoundaryCoordinates(double longitude, double latitude) {
        BuildingLocation location = new BuildingLocation(building, point(longitude, latitude));

        assertThat(location.getLocation().getX()).isEqualTo(longitude);
        assertThat(location.getLocation().getY()).isEqualTo(latitude);
    }

    @Test
    @DisplayName("건물 없이 위치를 생성할 수 없다")
    void rejectsMissingBuilding() {
        assertThatThrownBy(() -> new BuildingLocation(null, point(127, 37)))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("좌표 없이 위치 행을 생성할 수 없다")
    void rejectsMissingLocation() {
        assertThatThrownBy(() -> new BuildingLocation(building, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("빈 Point로 위치를 생성할 수 없다")
    void rejectsEmptyPoint() {
        assertThatThrownBy(() -> new BuildingLocation(building, GEOMETRY_FACTORY.createPoint()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("WGS 84가 아닌 Point로 위치를 생성할 수 없다")
    void rejectsOtherSpatialReferenceSystem() {
        Point point = point(127, 37);
        point.setSRID(0);

        assertThatThrownBy(() -> new BuildingLocation(building, point)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @CsvSource({
        "-180,37", "180.000001,37", "127,-90.000001", "127,90.000001",
        "NaN,37", "127,NaN", "Infinity,37", "127,-Infinity"
    })
    @DisplayName("좌표 범위를 벗어나거나 유한하지 않은 Point를 거부한다")
    void rejectsInvalidCoordinates(double longitude, double latitude) {
        assertThatThrownBy(() -> new BuildingLocation(building, point(longitude, latitude)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private Point point(double longitude, double latitude) {
        return GEOMETRY_FACTORY.createPoint(new Coordinate(longitude, latitude));
    }
}
