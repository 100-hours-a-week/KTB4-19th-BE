package com.homes.zipsai.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.ServerSocket;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.data.redis.autoconfigure.DataRedisProperties;
import org.springframework.boot.data.redis.autoconfigure.health.DataRedisHealthContributorAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

@DisplayName("Redis 접속 환경 설정")
class RedisConnectionConfigurationTest {

    private ApplicationContextRunner contextRunner;

    @BeforeEach
    void setUp() {
        contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DataRedisAutoConfiguration.class,
                DataRedisHealthContributorAutoConfiguration.class))
            .withPropertyValues("spring.config.location=file:src/main/resources/application.properties")
            .withInitializer(new ConfigDataApplicationContextInitializer());
    }

    @Test
    @DisplayName("환경변수로 Redis 접속 대상과 인증 및 TLS를 설정한다")
    void overridesConnectionWithEnvironmentVariables() {
        contextRunner.withInitializer(context -> context.getEnvironment().getPropertySources()
            .addFirst(new SystemEnvironmentPropertySource("redis-test", Map.of(
                "REDIS_HOST", "redis.example.test",
                "REDIS_PORT", "6380",
                "REDIS_USERNAME", "test-user",
                "REDIS_PASSWORD", "test-only-password",
                "REDIS_TLS", "true",
                "REDIS_CONNECT_TIMEOUT", "2s",
                "REDIS_TIMEOUT", "3s",
                "REDIS_SHUTDOWN_TIMEOUT", "4s"))))
            .run(context -> {
                assertThat(context).hasNotFailed();
                DataRedisProperties properties = context.getBean(DataRedisProperties.class);
                assertThat(properties.getHost()).isEqualTo("redis.example.test");

                assertThat(properties.getPort()).isEqualTo(6380);

                assertThat(properties.getUsername()).isEqualTo("test-user");

                assertThat(properties.getPassword()).isEqualTo("test-only-password");

                assertThat(properties.getSsl().isEnabled()).isTrue();

                assertThat(properties.getConnectTimeout()).isEqualTo(Duration.ofSeconds(2));

                assertThat(properties.getTimeout()).isEqualTo(Duration.ofSeconds(3));

                assertThat(properties.getLettuce().getShutdownTimeout()).isEqualTo(Duration.ofSeconds(4));

                LettuceConnectionFactory factory = context.getBean(LettuceConnectionFactory.class);

                assertThat(factory.getHostName()).isEqualTo("redis.example.test");

                assertThat(factory.getPort()).isEqualTo(6380);

                assertThat(factory.getClientConfiguration().isUseSsl()).isTrue();
            });
    }

    @Test
    @DisplayName("Redis 상태를 API 헬스 판정에 포함하지 않는다")
    void excludesRedisFromApiHealth() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();

            assertThat(context.getEnvironment().getProperty("management.health.redis.enabled")).isEqualTo("false");

            assertThat(context).doesNotHaveBean("redisHealthContributor");
        });
    }

    @Test
    @DisplayName("Redis 접속 불가 상태에서도 자동 구성은 초기화되고 접속 시도만 실패한다")
    void initializesWithoutAvailableRedis() throws Exception {
        int unavailablePort;
        try (ServerSocket socket = new ServerSocket(0)) {
            unavailablePort = socket.getLocalPort();
        }
        contextRunner.withPropertyValues("REDIS_PORT=" + unavailablePort).run(context -> {
            assertThat(context).hasNotFailed();

            assertThat(context).hasSingleBean(StringRedisTemplate.class);

            assertThat(context).doesNotHaveBean("redisHealthContributor");

            RedisConnectionFactory factory = context.getBean(RedisConnectionFactory.class);

            assertThatThrownBy(() -> {
                try (RedisConnection connection = factory.getConnection()) {
                    connection.ping();
                }
            }).isInstanceOf(RedisConnectionFailureException.class);
        });
    }

    @Test
    @DisplayName("환경변수로 지정한 실제 Redis에 Spring 기본 연결을 사용해 PING을 보낸다")
    void connectsToRedisWithEnvironmentVariables() {
        try (GenericContainer<?> redisContainer = new GenericContainer<>(DockerImageName.parse("redis:8.2.10-alpine"))
                .withExposedPorts(6379)) {
            redisContainer.start();
            contextRunner.withInitializer(context -> context.getEnvironment().getPropertySources()
                .addFirst(new SystemEnvironmentPropertySource("redis-test", Map.of(
                    "REDIS_HOST", redisContainer.getHost(),
                    "REDIS_PORT", redisContainer.getMappedPort(6379).toString()))))
                .run(context -> {
                    assertThat(context).hasNotFailed();

                    assertThat(context).hasSingleBean(StringRedisTemplate.class);

                    RedisConnectionFactory factory = context.getBean(RedisConnectionFactory.class);
                    try (RedisConnection connection = factory.getConnection()) {
                        assertThat(connection.ping()).isEqualTo("PONG");
                    }
                });
        }
    }
}
