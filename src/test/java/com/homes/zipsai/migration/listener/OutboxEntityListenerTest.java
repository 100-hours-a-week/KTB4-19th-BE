package com.homes.zipsai.migration.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.homes.zipsai.migration.config.MigrationConfiguration;
import com.homes.zipsai.migration.domain.MigrationMode;
import com.homes.zipsai.migration.domain.MigrationSettings;
import com.homes.zipsai.migration.domain.WorkerMode;
import com.homes.zipsai.migration.service.MigrationRouting;
import com.homes.zipsai.migration.service.MigrationSettingsProvider;
import com.homes.zipsai.migration.service.MigrationTestUserService;
import com.homes.zipsai.migration.service.OutboxEntityTables;
import com.homes.zipsai.migration.service.OutboxRecorder;
import com.homes.zipsai.user.domain.User;
import com.homes.zipsai.user.repository.UserRepository;

@DataJpaTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:outbox_listener;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.jpa.hibernate.naming.physical-strategy="
        + "org.hibernate.boot.model.naming.PhysicalNamingStrategyStandardImpl",
    "app.migration.v2-datasource.url=jdbc:h2:mem:outbox_listener_v2"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({MigrationConfiguration.class, OutboxEntityListener.class, OutboxRecorder.class, OutboxEntityTables.class,
    MigrationSettingsProvider.class, MigrationRouting.class, MigrationTestUserService.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OutboxEntityListenerTest {

    private static final String RESIDENT_EMAIL = "outbox-resident@example.com";
    private static final MigrationSettings OUTBOX_ON =
        new MigrationSettings(MigrationMode.V1_PRIMARY, null, WorkerMode.PAUSED, true, true, 0, false);

    @Autowired
    UserRepository userRepository;

    @Autowired
    MigrationSettingsProvider migrationSettingsProvider;

    @Autowired
    MigrationTestUserService migrationTestUserService;

    @Autowired
    DataSource dataSource;

    JdbcClient jdbcClient;

    @BeforeEach
    void setUp() {
        jdbcClient = JdbcClient.create(dataSource);
        jdbcClient.sql("DROP TABLE IF EXISTS Migration_outbox_events").update();
        jdbcClient.sql("DROP TABLE IF EXISTS Migration_applied_events").update();
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V19__migration_outbox_events.sql"))
            .execute(dataSource);
        migrationSettingsProvider.update(OUTBOX_ON);
    }

    @AfterEach
    void tearDown() {
        migrationSettingsProvider.update(MigrationSettings.DEFAULT);
        jdbcClient.sql("DELETE FROM Users WHERE email = ?")
            .param(RESIDENT_EMAIL)
            .update();
    }

    @Test
    @DisplayName("엔티티를 저장하면 테이블 이름과 ID로 INSERT 이벤트를 기록한다")
    void recordsInsertEvent() {
        User user = userRepository.save(resident());

        assertThat(events()).containsExactly(new RecordedEvent("V1_TO_V2", "Users", user.getId().toString(),
            "INSERT", "PENDING"));
    }

    @Test
    @DisplayName("엔티티를 수정하면 UPDATE 이벤트를 기록한다")
    void recordsUpdateEvent() {
        User user = userRepository.save(resident());

        migrationTestUserService.changeUserName(user.getId(), "새이름");

        assertThat(events()).extracting(RecordedEvent::operation).containsExactly("INSERT", "UPDATE");
    }

    @Test
    @DisplayName("엔티티를 삭제하면 DELETE 이벤트를 기록한다")
    void recordsDeleteEvent() {
        User user = userRepository.save(resident());

        userRepository.deleteById(user.getId());

        assertThat(events()).extracting(RecordedEvent::operation).containsExactly("INSERT", "DELETE");
    }

    @Test
    @DisplayName("저장이 롤백되면 이벤트도 남지 않는다")
    void discardsEventWhenTransactionRollsBack() {
        assertThatThrownBy(() -> migrationTestUserService.saveAndFail(resident()))
            .isInstanceOf(IllegalStateException.class);

        assertThat(events()).isEmpty();
    }

    @Test
    @DisplayName("V2를 기준 DB로 쓰는 동안에는 V2에서 V1으로 가는 이벤트로 기록한다")
    void recordsReverseDirectionWhenV2IsPrimary() {
        migrationSettingsProvider.update(
            new MigrationSettings(MigrationMode.V2_PRIMARY, null, WorkerMode.PAUSED, true, true, 0, false));

        userRepository.save(resident());

        assertThat(events()).extracting(RecordedEvent::direction).containsExactly("V2_TO_V1");
    }

    private User resident() {
        return new User(RESIDENT_EMAIL, "password", "입주민", null);
    }

    private List<RecordedEvent> events() {
        return jdbcClient.sql("""
                SELECT direction, entity_type, entity_id, operation, status
                FROM Migration_outbox_events ORDER BY event_id
                """)
            .query(RecordedEvent.class)
            .list();
    }

    record RecordedEvent(String direction, String entityType, String entityId, String operation, String status) {
    }
}
