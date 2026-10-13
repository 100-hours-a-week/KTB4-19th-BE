package com.homes.zipsai.migration.scheduler;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.homes.zipsai.migration.config.ConditionalOnMigrationEnabled;
import com.homes.zipsai.migration.config.MigrationProperties;
import com.homes.zipsai.migration.domain.MigrationSettings;
import com.homes.zipsai.migration.service.MigrationSettingsProvider;

@Component
@ConditionalOnMigrationEnabled
public class MigrationSettingsPoller {

    private static final Logger LOGGER = LoggerFactory.getLogger(MigrationSettingsPoller.class);
    private static final int POLL_INTERVAL_SECONDS = 15;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(1);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(2);

    private final RestClient restClient;
    private final URI appConfigUri;
    private final MigrationSettingsProvider migrationSettingsProvider;

    public MigrationSettingsPoller(RestClient.Builder builder, MigrationProperties migrationProperties,
                                   MigrationSettingsProvider migrationSettingsProvider) {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
            HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
        requestFactory.setReadTimeout(READ_TIMEOUT);
        this.restClient = builder.requestFactory(requestFactory).build();
        this.appConfigUri = URI.create(migrationProperties.appConfigUrl());
        this.migrationSettingsProvider = migrationSettingsProvider;
    }

    @PostConstruct
    void loadInitialSettings() {
        try {
            migrationSettingsProvider.update(fetchSettings());
        } catch (RestClientException | IllegalStateException exception) {
            throw new IllegalStateException("이전 설정을 가져오지 못해 쓰기 DB를 정할 수 없으므로 시작하지 않습니다.", exception);
        }
    }

    @Scheduled(fixedDelay = POLL_INTERVAL_SECONDS, initialDelay = POLL_INTERVAL_SECONDS, timeUnit = TimeUnit.SECONDS)
    public void pollSettings() {
        try {
            migrationSettingsProvider.update(fetchSettings());
        } catch (RestClientException | IllegalStateException exception) {
            LOGGER.warn("이전 설정을 가져오지 못해 마지막 설정을 유지합니다. 설정={}",
                migrationSettingsProvider.getSettings(), exception);
        }
    }

    private MigrationSettings fetchSettings() {
        AppConfigDocument document = restClient.get()
            .uri(appConfigUri)
            .retrieve()
            .body(AppConfigDocument.class);
        if (document == null || document.migration() == null) {
            throw new IllegalStateException("AppConfig에 migration 설정이 없습니다.");
        }
        return document.migration();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record AppConfigDocument(MigrationSettings migration) {
    }
}
