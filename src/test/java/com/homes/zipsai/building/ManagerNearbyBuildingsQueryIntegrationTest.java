package com.homes.zipsai.building;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.homes.zipsai.building.domain.Building;
import com.homes.zipsai.building.domain.InvitationCode;
import com.homes.zipsai.building.domain.Room;
import com.homes.zipsai.building.domain.RoomStatus;

import tools.jackson.databind.JsonNode;

class ManagerNearbyBuildingsQueryIntegrationTest extends NearbyBuildingIntegrationTestSupport {

    @Test
    @DisplayName("사각형의 네 변과 네 꼭짓점은 포함하고 바로 바깥 좌표는 제외한다")
    void includesEveryEdgeAndCornerButExcludesPointsJustOutside() throws Exception {
        double[][] boundary = {
            {37, 127}, {37, 128}, {38, 127}, {38, 128},
            {37, 127.5}, {38, 127.5}, {37.5, 127}, {37.5, 128}
        };
        List<Long> expected = new ArrayList<>();
        expected.add(ownBuilding.getId());
        for (double[] coordinate : boundary) {
            expected.add(locatedBuilding("경계건물", coordinate[0], coordinate[1]).getId());
        }
        locatedBuilding("남쪽외부", 36.999999, 127.5);
        locatedBuilding("북쪽외부", 38.000001, 127.5);
        locatedBuilding("서쪽외부", 37.5, 126.999999);
        locatedBuilding("동쪽외부", 37.5, 128.000001);

        assertThat(ids(success(bounds()))).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    @DisplayName("만료된 초대중 호실도 공실로 집계하고 삭제 호실은 제외한다")
    void countsInvitedRoomsAsEmptyRegardlessOfInvitationExpiry() throws Exception {
        room(ownBuilding, "101", RoomStatus.EMPTY);
        Room expired = room(ownBuilding, "102", RoomStatus.INVITED);
        InvitationCode code = new InvitationCode(expired, "123456", LocalDateTime.now().minusDays(1));
        code.expire();
        entityManager.persist(code);
        room(ownBuilding, "103", RoomStatus.INVITED);
        room(ownBuilding, "104", RoomStatus.LIVING);
        softDelete(room(ownBuilding, "105", RoomStatus.EMPTY));
        softDelete(room(ownBuilding, "106", RoomStatus.INVITED));
        softDelete(room(ownBuilding, "107", RoomStatus.LIVING));

        assertBuilding(item(success(bounds()), ownBuilding), ownBuilding, 37.5, 127.5, 4, 1, 3);

        assertThat(entityManager.find(Room.class, expired.getId()).getStatus()).isEqualTo(RoomStatus.INVITED);
    }

    @Test
    @DisplayName("건물 이름 null을 유지하고 활성 호실이 없으면 요약은 모두 0이다")
    void preservesNullNameAndReturnsZeroCountsForNoActiveRooms() throws Exception {
        Building unnamed = locatedBuilding(null, 37.6, 127.6);
        softDelete(room(unnamed, "101", RoomStatus.LIVING));

        JsonNode data = success(bounds());

        assertBuilding(item(data, ownBuilding), ownBuilding, 37.5, 127.5, 0, 0, 0);

        assertBuilding(item(data, unnamed), unnamed, 37.6, 127.6, 0, 0, 0);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 20})
    @DisplayName("건물 수와 관계없이 조회 수를 제한하고 데이터 쓰기와 Kakao 호출을 하지 않는다")
    void aggregatesRoomsWithBoundedQueriesWithoutWritingOrGeocoding(int buildingCount) throws Exception {
        room(ownBuilding, "101", RoomStatus.EMPTY);
        for (int i = 1; i < buildingCount; i++) {
            Building other = locatedBuilding("일괄집계", 37.6, 127.6);
            room(other, "101", RoomStatus.INVITED);
            room(other, "102", RoomStatus.LIVING);
        }
        Statistics statistics = resetStatistics();

        JsonNode data = success(bounds());

        assertThat(ids(data)).hasSize(buildingCount);

        assertThat(statistics.getPrepareStatementCount()).isLessThanOrEqualTo(3);

        assertThat(statistics.getEntityInsertCount()).isZero();

        assertThat(statistics.getEntityUpdateCount()).isZero();

        assertThat(statistics.getEntityDeleteCount()).isZero();

        verifyNoInteractions(kakaoAddressClient);
    }

    private Statistics resetStatistics() {
        entityManager.flush();
        entityManager.clear();
        Statistics statistics = entityManager.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();

        assertThat(statistics.isStatisticsEnabled()).isTrue();

        statistics.clear();
        return statistics;
    }

}
