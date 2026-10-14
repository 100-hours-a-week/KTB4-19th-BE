package com.homes.zipsai.migration.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;

import java.util.List;
import java.util.Optional;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.homes.zipsai.migration.aspect.ShadowReadAspect;
import com.homes.zipsai.migration.domain.MigrationDatabase;
import com.homes.zipsai.migration.domain.MigrationMode;
import com.homes.zipsai.migration.domain.MigrationSettings;
import com.homes.zipsai.migration.domain.WorkerMode;
import com.homes.zipsai.migration.listener.OutboxEntityListener;
import com.homes.zipsai.migration.scheduler.MigrationSettingsPoller;
import com.homes.zipsai.migration.service.MigrationEventLogger;
import com.homes.zipsai.migration.service.MigrationMetrics;
import com.homes.zipsai.migration.service.MigrationRouting;
import com.homes.zipsai.migration.service.MigrationSettingsProvider;
import com.homes.zipsai.migration.service.MigrationTestUserService;
import com.homes.zipsai.migration.service.OutboxEntityTables;
import com.homes.zipsai.migration.service.OutboxRecorder;
import com.homes.zipsai.migration.service.ShadowReadComparator;
import com.homes.zipsai.migration.service.ShadowReadRowVerifier;
import com.homes.zipsai.user.domain.User;
import com.homes.zipsai.user.repository.UserRepository;

@DataJpaTest(properties = {
    "spring.datasource.url=jdbc:tc:mysql:8.4.11:///routing_v1?TC_DAEMON=true",
    "spring.datasource.username=test",
    "spring.datasource.password=test",
    "spring.jpa.hibernate.ddl-auto=none",
    "spring.jpa.hibernate.naming.physical-strategy="
        + "org.hibernate.boot.model.naming.PhysicalNamingStrategyStandardImpl",
    "spring.jpa.defer-datasource-initialization=false",
    "spring.sql.init.mode=never",
    "spring.flyway.enabled=true",
    "app.migration.v2-datasource.url=jdbc:tc:mysql:8.4.11:///routing_v2?TC_DAEMON=true",
    "app.migration.v2-datasource.username=test",
    "app.migration.v2-datasource.password=test"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnableAspectJAutoProxy
@ImportAutoConfiguration(JacksonAutoConfiguration.class)
@Import({MigrationConfiguration.class, MigrationRoutingConfiguration.class, MigrationRouting.class,
    MigrationSettingsProvider.class, OutboxEntityListener.class, OutboxRecorder.class, OutboxEntityTables.class,
    ShadowReadAspect.class, ShadowReadComparator.class, ShadowReadRowVerifier.class, MigrationTestUserService.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class MigrationRoutingIntegrationTest {

    private static final String EMAIL = "routing@example.com";

    @Autowired
    UserRepository userRepository;

    @Autowired
    MigrationTestUserService migrationTestUserService;

    @Autowired
    MigrationSettingsProvider migrationSettingsProvider;

    @Autowired
    @Qualifier("migrationDataSources")
    MigrationRoutingConfiguration.MigrationDataSources migrationDataSources;

    @MockitoSpyBean
    ShadowReadComparator shadowReadComparator;

    @MockitoBean(name = "migrationSettingsPoller")
    MigrationSettingsPoller migrationSettingsPoller;

    @MockitoBean
    MigrationMetrics migrationMetrics;

    @MockitoBean
    MigrationEventLogger migrationEventLogger;

    JdbcClient v1;

    JdbcClient v2;

    @BeforeEach
    void setUp() {
        Flyway.configure().dataSource(migrationDataSources.v2()).load().migrate();
        v1 = JdbcClient.create(migrationDataSources.v1());
        v2 = JdbcClient.create(migrationDataSources.v2());
        givenSettings(MigrationMode.V1_PRIMARY, 0, 0);
    }

    @AfterEach
    void tearDown() {
        for (JdbcClient database : List.of(v1, v2)) {
            database.sql("DELETE FROM Users WHERE email = ?").param(EMAIL).update();
            database.sql("DELETE FROM Migration_outbox_events").update();
        }
        migrationSettingsProvider.update(MigrationSettings.DEFAULT);
    }

    @Test
    @DisplayName("V1_PRIMARY면 V1에 저장하고 V1 Outbox에 V1에서 V2로 가는 이벤트를 남긴다")
    void writesToV1WhenV1IsPrimary() {
        userRepository.save(resident("V1 사용자"));

        assertThat(userNames(v1)).containsExactly("V1 사용자");
        assertThat(userNames(v2)).isEmpty();
        assertThat(outboxDirections(v1)).containsExactly("V1_TO_V2");
    }

    @Test
    @DisplayName("V2_PRIMARY면 V2에 저장하고 V2 Outbox에 V2에서 V1으로 가는 이벤트를 남긴다")
    void writesToV2WhenV2IsPrimary() {
        givenSettings(MigrationMode.V2_PRIMARY, 0, 0);

        userRepository.save(resident("V2 사용자"));

        assertThat(userNames(v2)).containsExactly("V2 사용자");
        assertThat(userNames(v1)).isEmpty();
        assertThat(outboxDirections(v2)).containsExactly("V2_TO_V1");
    }

    @Test
    @DisplayName("트랜잭션 중에 V2_PRIMARY로 바뀌어도 그 트랜잭션은 V1에 쓰고 V1 기준으로 기록한다")
    void keepsWritingToV1WhenModeChangesDuringTransaction() {
        migrationTestUserService.saveAndRenameAfter(resident("처음 이름"),
            () -> givenSettings(MigrationMode.V2_PRIMARY, 0, 0), "바뀐 이름");

        assertThat(userNames(v1)).containsExactly("바뀐 이름");
        assertThat(userNames(v2)).isEmpty();
        assertThat(outboxDirections(v1)).containsExactly("V1_TO_V2", "V1_TO_V2");
    }

    @Test
    @DisplayName("V2 읽기 비율이 100이면 읽기 전용 조회는 V2에서 읽는다")
    void readsFromV2WhenReadPercentIsHundred() {
        saveUser(v1, "V1 이름");
        saveUser(v2, "V2 이름");
        givenSettings(MigrationMode.V1_PRIMARY, 100, 0);

        Optional<String> userName = migrationTestUserService.findUserName(EMAIL);

        assertThat(userName).contains("V2 이름");
    }

    @Test
    @DisplayName("V2 읽기 비율이 0이면 읽기 전용 조회는 V1에서 읽는다")
    void readsFromV1WhenReadPercentIsZero() {
        saveUser(v1, "V1 이름");
        saveUser(v2, "V2 이름");

        Optional<String> userName = migrationTestUserService.findUserName(EMAIL);

        assertThat(userName).contains("V1 이름");
    }

    @Test
    @DisplayName("Shadow Read가 켜져 있으면 V1 결과로 응답하고 V2 결과와 비교한다")
    void comparesWithV2WhenShadowReadIsEnabled() {
        saveUser(v1, "V1 이름");
        saveUser(v2, "V2 이름");
        givenSettings(MigrationMode.V1_PRIMARY, 0, 100);

        Optional<String> userName = migrationTestUserService.findUserName(EMAIL);

        assertThat(userName).contains("V1 이름");
        then(shadowReadComparator).should(timeout(3000)).compare(
            argThat(query -> query.database() == MigrationDatabase.V1
                && query.result().equals(Optional.of("V1 이름"))),
            argThat(query -> query.database() == MigrationDatabase.V2
                && query.result().equals(Optional.of("V2 이름"))));
    }

    @Test
    @DisplayName("Shadow Read가 꺼져 있으면 V2와 비교하지 않는다")
    void skipsComparisonWhenShadowReadIsDisabled() {
        saveUser(v1, "V1 이름");

        migrationTestUserService.findUserName(EMAIL);

        then(shadowReadComparator).should(after(500).never()).compare(any(), any());
    }

    private void givenSettings(MigrationMode mode, int v2ReadPercent, int shadowReadPercent) {
        migrationSettingsProvider.update(
            new MigrationSettings(mode, null, WorkerMode.PAUSED, true, true, v2ReadPercent, shadowReadPercent));
    }

    private User resident(String userName) {
        return new User(EMAIL, "password", userName, null);
    }

    private void saveUser(JdbcClient database, String userName) {
        database.sql("""
                INSERT INTO Users (email, password, user_name, user_role, user_status, created_at, updated_at)
                VALUES (?, 'password', ?, 'NONE', 'ACTIVE', NOW(), NOW())
                """)
            .params(EMAIL, userName)
            .update();
    }

    private List<String> userNames(JdbcClient database) {
        return database.sql("SELECT user_name FROM Users WHERE email = ?")
            .param(EMAIL)
            .query(String.class)
            .list();
    }

    private List<String> outboxDirections(JdbcClient database) {
        return database.sql("SELECT direction FROM Migration_outbox_events ORDER BY event_id")
            .query(String.class)
            .list();
    }
}
