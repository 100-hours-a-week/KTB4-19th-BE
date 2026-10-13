package com.homes.zipsai.migration.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import com.homes.zipsai.migration.config.MigrationProperties;
import com.homes.zipsai.migration.domain.MigrationMode;
import com.homes.zipsai.migration.domain.MigrationSettings;
import com.homes.zipsai.migration.domain.WorkerMode;
import com.homes.zipsai.migration.service.MigrationEventLogger;
import com.homes.zipsai.migration.service.MigrationSettingsProvider;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

class MigrationSettingsPollerTest {

    private static final String APP_CONFIG_PATH = "/applications/zipsai/environments/prod/configurations/db-migration";
    private static final MigrationSettings RUNNING =
        new MigrationSettings(MigrationMode.V1_PRIMARY, null, WorkerMode.RUNNING, true, true, 0, 0);

    HttpServer appConfigAgent;

    int agentStatus;

    String agentBody;

    MigrationSettingsProvider migrationSettingsProvider;

    MigrationEventLogger migrationEventLogger;

    MigrationSettingsPoller migrationSettingsPoller;

    @BeforeEach
    void setUp() throws IOException {
        appConfigAgent = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        appConfigAgent.createContext(APP_CONFIG_PATH, this::respond);
        appConfigAgent.start();
        migrationSettingsProvider = new MigrationSettingsProvider();
        migrationEventLogger = mock(MigrationEventLogger.class);
        migrationSettingsPoller = poller();
    }

    @AfterEach
    void tearDown() {
        appConfigAgent.stop(0);
    }

    @Test
    @DisplayName("Cloud가 정한 AppConfig 최종 형식을 그대로 읽는다")
    void readsFinalAppConfigFormat() {
        migrationSettingsProvider.update(RUNNING);
        givenConfiguration("""
            {"migration": {"mode": "V1_PRIMARY", "switchAt": null, "workerMode": "PAUSED",
                "forwardWorkerEnabled": false, "reverseWorkerEnabled": false,
                "v2ReadPercent": 0, "shadowReadPercent": 0}}
            """);

        migrationSettingsPoller.pollSettings();

        assertThat(migrationSettingsProvider.getSettings()).isEqualTo(MigrationSettings.DEFAULT);
    }

    @Test
    @DisplayName("AppConfig의 mode와 전환 시각, workerMode, 방향별 워커 여부를 적용한다")
    void appliesSettingsFromAppConfig() {
        givenConfiguration("""
            {"migration": {"mode": "V2_PRIMARY", "switchAt": "2026-10-20T14:00:00+09:00", "workerMode": "DRAINING",
                "forwardWorkerEnabled": true, "reverseWorkerEnabled": false}}
            """);

        migrationSettingsPoller.pollSettings();

        assertThat(migrationSettingsProvider.getSettings())
            .usingRecursiveComparison()
            .withComparatorForType(OffsetDateTime.timeLineOrder(), OffsetDateTime.class)
            .isEqualTo(new MigrationSettings(MigrationMode.V2_PRIMARY,
                OffsetDateTime.parse("2026-10-20T14:00:00+09:00"), WorkerMode.DRAINING, true, false, 0, 0));
    }

    @Test
    @DisplayName("V2 읽기 비율과 Shadow Read 여부를 적용한다")
    void appliesReadSettings() {
        givenConfiguration("""
            {"migration": {"mode": "V1_PRIMARY", "workerMode": "RUNNING",
                "v2ReadPercent": 10, "shadowReadPercent": 30}}
            """);

        migrationSettingsPoller.pollSettings();

        assertThat(migrationSettingsProvider.getSettings())
            .isEqualTo(
                new MigrationSettings(MigrationMode.V1_PRIMARY, null, WorkerMode.RUNNING, false, false, 10, 30));
    }

    @Test
    @DisplayName("설정이 바뀌면 바뀐 설정을 구조화 로그로 남긴다")
    void logsChangedSettings() {
        migrationSettingsProvider.update(RUNNING);
        givenConfiguration("""
            {"migration": {"mode": "V2_PRIMARY", "workerMode": "RUNNING"}}
            """);

        migrationSettingsPoller.pollSettings();

        then(migrationEventLogger).should().settingsChanged(migrationSettingsProvider.getSettings());
    }

    @Test
    @DisplayName("설정이 그대로면 구조화 로그를 남기지 않는다")
    void skipsLoggingUnchangedSettings() {
        migrationSettingsProvider.update(RUNNING);
        givenConfiguration("""
            {"migration": {"mode": "V1_PRIMARY", "workerMode": "RUNNING",
                "forwardWorkerEnabled": true, "reverseWorkerEnabled": true}}
            """);

        migrationSettingsPoller.pollSettings();

        then(migrationEventLogger).should(never()).settingsChanged(any());
    }

    @Test
    @DisplayName("V2 읽기 비율이 100을 넘으면 마지막 설정을 유지한다")
    void keepsSettingsWhenReadPercentIsOutOfRange() {
        migrationSettingsProvider.update(RUNNING);
        givenConfiguration("""
            {"migration": {"mode": "V1_PRIMARY", "workerMode": "RUNNING",
                "v2ReadPercent": 101}}
            """);

        migrationSettingsPoller.pollSettings();

        assertThat(migrationSettingsProvider.getSettings()).isEqualTo(RUNNING);
    }

    @Test
    @DisplayName("방향별 워커 여부가 없으면 두 방향 모두 끈다")
    void disablesWorkersWhenDirectionFlagsAreMissing() {
        givenConfiguration("""
            {"migration": {"mode": "V1_PRIMARY", "workerMode": "RUNNING"}}
            """);

        migrationSettingsPoller.pollSettings();

        MigrationSettings settings = migrationSettingsProvider.getSettings();
        assertThat(settings.forwardWorkerEnabled()).isFalse();
        assertThat(settings.reverseWorkerEnabled()).isFalse();
    }

    @Test
    @DisplayName("workerMode가 빠지면 마지막 설정을 유지한다")
    void keepsSettingsWhenWorkerModeIsMissing() {
        migrationSettingsProvider.update(RUNNING);
        givenConfiguration("""
            {"migration": {"mode": "V2_PRIMARY"}}
            """);

        migrationSettingsPoller.pollSettings();

        assertThat(migrationSettingsProvider.getSettings()).isEqualTo(RUNNING);
    }

    @Test
    @DisplayName("모르는 mode 값이면 마지막 설정을 유지한다")
    void keepsSettingsWhenModeIsUnknown() {
        migrationSettingsProvider.update(RUNNING);
        givenConfiguration("""
            {"migration": {"mode": "V3_PRIMARY", "workerMode": "RUNNING"}}
            """);

        migrationSettingsPoller.pollSettings();

        assertThat(migrationSettingsProvider.getSettings()).isEqualTo(RUNNING);
    }

    @Test
    @DisplayName("migration 설정이 없으면 마지막 설정을 유지한다")
    void keepsSettingsWhenMigrationIsMissing() {
        migrationSettingsProvider.update(RUNNING);
        givenConfiguration("{}");

        migrationSettingsPoller.pollSettings();

        assertThat(migrationSettingsProvider.getSettings()).isEqualTo(RUNNING);
    }

    @Test
    @DisplayName("AppConfig를 조회하지 못하면 마지막 설정을 유지한다")
    void keepsSettingsWhenAppConfigFails() {
        migrationSettingsProvider.update(RUNNING);
        givenAgentFailure();

        migrationSettingsPoller.pollSettings();

        assertThat(migrationSettingsProvider.getSettings()).isEqualTo(RUNNING);
    }

    @Test
    @DisplayName("시작할 때 AppConfig를 조회하지 못하면 시작하지 않는다")
    void failsToStartWhenInitialLoadFails() {
        givenAgentFailure();

        assertThatThrownBy(migrationSettingsPoller::loadInitialSettings).isInstanceOf(IllegalStateException.class);
    }

    private MigrationSettingsPoller poller() {
        String appConfigUrl = "http://localhost:" + appConfigAgent.getAddress().getPort() + APP_CONFIG_PATH;
        return new MigrationSettingsPoller(RestClient.builder(), new MigrationProperties(appConfigUrl, null),
            migrationSettingsProvider, migrationEventLogger);
    }

    private void givenConfiguration(String json) {
        agentStatus = 200;
        agentBody = json;
    }

    private void givenAgentFailure() {
        agentStatus = 500;
        agentBody = "";
    }

    private void respond(HttpExchange exchange) throws IOException {
        byte[] body = agentBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(agentStatus, body.length == 0 ? -1 : body.length);
        try (OutputStream responseBody = exchange.getResponseBody()) {
            responseBody.write(body);
        }
    }
}
