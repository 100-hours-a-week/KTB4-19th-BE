package com.homes.zipsai.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import com.homes.zipsai.global.config.SchedulingConfig;

@DisplayName("SSE 환경 설정")
class SsePropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(SchedulingConfig.class);

    @Test
    @DisplayName("별도 설정이 없으면 heartbeat 30초와 연결 timeout 5분을 사용한다")
    void bindsDefaultHeartbeatAndTimeout() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            SseProperties properties = context.getBean(SseProperties.class);
            assertThat(properties.heartbeat()).isEqualTo(Duration.ofSeconds(30));
            assertThat(properties.timeout()).isEqualTo(Duration.ofMinutes(5));
        });
    }

    @Test
    @DisplayName("환경 변수로 heartbeat와 연결 timeout을 변경할 수 있다")
    void overridesDurationsWithEnvironmentVariables() {
        contextRunner.withInitializer(context -> context.getEnvironment().getPropertySources()
            .addFirst(new SystemEnvironmentPropertySource("sse-test", Map.of(
                "APP_SSE_HEARTBEAT", "5s",
                "APP_SSE_TIMEOUT", "10m"))))
            .run(context -> {
                assertThat(context).hasNotFailed();
                SseProperties properties = context.getBean(SseProperties.class);
                assertThat(properties.heartbeat()).isEqualTo(Duration.ofSeconds(5));
                assertThat(properties.timeout()).isEqualTo(Duration.ofMinutes(10));
            });
    }

    @Test
    @DisplayName("heartbeat가 0이면 잘못된 설정으로 시작을 거부한다")
    void rejectsZeroHeartbeat() {
        contextRunner.withPropertyValues("app.sse.heartbeat=0s")
            .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("연결 timeout이 음수이면 잘못된 설정으로 시작을 거부한다")
    void rejectsNegativeTimeout() {
        contextRunner.withPropertyValues("app.sse.timeout=-1s")
            .run(context -> assertThat(context).hasFailed());
    }
}
