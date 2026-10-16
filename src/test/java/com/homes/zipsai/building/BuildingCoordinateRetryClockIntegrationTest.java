package com.homes.zipsai.building;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.TimeZone;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import com.homes.zipsai.building.domain.Building;
import com.homes.zipsai.building.domain.BuildingCoordinateRetryJob;
import com.homes.zipsai.building.repository.BuildingCoordinateRetryJobRepository;
import com.homes.zipsai.building.service.BuildingCoordinateRetryClaimService;
import com.homes.zipsai.user.domain.User;

@SpringBootTest
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:tc:mysql:8.4.11:///zipsai_coordinate_retry_clock?TC_DAEMON=true"
        + "&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
    "spring.datasource.username=test",
    "spring.datasource.password=test",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.jpa.hibernate.naming.physical-strategy=org.hibernate.boot.model.naming.PhysicalNamingStrategyStandardImpl",
    "spring.jpa.defer-datasource-initialization=false",
    "spring.flyway.enabled=true"
})
class BuildingCoordinateRetryClockIntegrationTest {

    private static TimeZone originalTimeZone;

    @BeforeAll
    static void useApplicationTimeZone() {
        originalTimeZone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));
    }

    @AfterAll
    static void restoreTimeZone() {
        TimeZone.setDefault(originalTimeZone);
    }

    @Autowired
    BuildingCoordinateRetryClaimService buildingCoordinateRetryClaimService;

    @Autowired
    BuildingCoordinateRetryJobRepository buildingCoordinateRetryJobRepository;

    @Autowired
    EntityManager entityManager;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("DB 세션이 UTC여도 기존 등록과 같은 애플리케이션 시각의 작업을 제때 선점한다")
    void usesExistingRegistrationTimeZone() {
        assertThat(jdbcTemplate.queryForObject(
            "SELECT TIMESTAMPDIFF(MINUTE, UTC_TIMESTAMP(), CURRENT_TIMESTAMP())", Integer.class)).isZero();

        Long id = transactionTemplate.execute(transaction -> {
            User manager = new User(UUID.randomUUID() + "@example.com", "password", "관리자", null);
            entityManager.persist(manager);
            Building building = new Building(manager, "서울 테스트로 1", null);
            entityManager.persist(building);
            buildingCoordinateRetryJobRepository.saveAndFlush(new BuildingCoordinateRetryJob(building, "NETWORK_ERROR",
                LocalDateTime.now().minusSeconds(1)));
            return building.getId();
        });
        LocalDateTime before = LocalDateTime.now().minusSeconds(1);

        var result = buildingCoordinateRetryClaimService.claimNext();

        assertThat(result).isPresent();

        assertThat(result.orElseThrow().buildingId()).isEqualTo(id);

        assertThat(buildingCoordinateRetryJobRepository.findById(id).orElseThrow().getLeaseExpiresAt())
            .isBetween(before.plusMinutes(10), LocalDateTime.now().plusMinutes(10));
    }
}
