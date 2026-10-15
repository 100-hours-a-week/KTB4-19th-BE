package com.homes.zipsai.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import com.homes.zipsai.global.exception.InvalidPubSubFieldException;
import com.homes.zipsai.global.exception.InvalidPubSubFieldException.Reason;

@DisplayName("Redis Pub/Sub 알림 채널 설정")
class RedisPubSubPropertiesTest {

    private ApplicationContextRunner contextRunner;

    @BeforeEach
    void setUp() {
        contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfiguration.class)
            .withPropertyValues("spring.config.location=file:src/main/resources/application.properties")
            .withInitializer(new ConfigDataApplicationContextInitializer());
    }

    @Test
    @DisplayName("별도 알림 채널 설정이 없으면 notification-events를 사용한다")
    void bindsDefaultNotificationChannel() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();

            assertThat(context.getBean(RedisPubSubProperties.class).notificationChannel())
                .isEqualTo("notification-events");
        });
    }

    private ApplicationContextRunner withNotificationChannel(String notificationChannel) {
        return contextRunner.withInitializer(context -> context.getEnvironment().getPropertySources()
            .addFirst(new SystemEnvironmentPropertySource("pubsub-test",
                Map.of("REDIS_NOTIFICATION_PUBSUB_CHANNEL", notificationChannel))));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(RedisPubSubProperties.class)
    static class TestConfiguration {
    }
}
