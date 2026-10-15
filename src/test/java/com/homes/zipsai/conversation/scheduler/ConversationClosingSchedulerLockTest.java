package com.homes.zipsai.conversation.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import com.homes.zipsai.conversation.service.ConversationService;
import com.homes.zipsai.global.config.SchedulerLockConfig;

class ConversationClosingSchedulerLockTest {

    private static final String LOCK_KEY = "job-lock:default:closeIdleConversations";
    private static final Duration LOCK_AT_LEAST_FOR = Duration.ofMinutes(5);
    private static final Duration LOCK_AT_MOST_FOR = Duration.ofMinutes(10);
    private static final String OTHER_SERVER_LOCK = "other-server-lock";
    private static final GenericContainer<?> REDIS_CONTAINER =
        new GenericContainer<>(DockerImageName.parse("redis:8.2.10-alpine")).withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate stringRedisTemplate;

    @BeforeAll
    static void startRedis() {
        REDIS_CONTAINER.start();
        connectionFactory = connectionFactory(REDIS_CONTAINER.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        stringRedisTemplate = new StringRedisTemplate(connectionFactory);
    }

    @AfterAll
    static void stopRedis() {
        connectionFactory.destroy();
        REDIS_CONTAINER.stop();
    }

    @BeforeEach
    void setUp() {
        stringRedisTemplate.delete(LOCK_KEY);
    }

    @Test
    @DisplayName("락이 비어 있으면 대화방 종료 처리를 실행한다")
    void closesIdleConversationsWhenLockIsFree() {
        contextRunnerWithRedis(REDIS_CONTAINER.getMappedPort(6379)).run(context -> {
            context.getBean(ConversationClosingScheduler.class).closeIdleConversations();

            then(context.getBean(ConversationService.class)).should().closeIdleConversations(any());
        });
    }

    @Test
    @DisplayName("다른 서버가 락을 잡고 있으면 대화방 종료 처리를 실행하지 않는다")
    void skipsClosingWhenLockIsHeld() {
        stringRedisTemplate.opsForValue().set(LOCK_KEY, "locked");

        contextRunnerWithRedis(REDIS_CONTAINER.getMappedPort(6379)).run(context -> {
            context.getBean(ConversationClosingScheduler.class).closeIdleConversations();

            then(context.getBean(ConversationService.class)).should(never()).closeIdleConversations(any());
        });
    }

    @Test
    @DisplayName("종료 처리가 끝나도 락은 5분 동안 유지한다")
    void keepsLockForFiveMinutesAfterClosing() {
        contextRunnerWithRedis(REDIS_CONTAINER.getMappedPort(6379)).run(context -> {
            context.getBean(ConversationClosingScheduler.class).closeIdleConversations();

            Long ttlSeconds = stringRedisTemplate.getExpire(LOCK_KEY, TimeUnit.SECONDS);
            assertThat(ttlSeconds).isPositive()
                .isLessThanOrEqualTo(LOCK_AT_LEAST_FOR.toSeconds());
        });
    }

    @Test
    @DisplayName("종료 처리 중에는 락을 최대 10분 동안 잡는다")
    void holdsLockForTenMinutesWhileClosing() {
        contextRunnerWithRedis(REDIS_CONTAINER.getMappedPort(6379)).run(context -> {
            List<Long> ttlSecondsWhileClosing = new ArrayList<>();
            willAnswer(invocation -> {
                ttlSecondsWhileClosing.add(stringRedisTemplate.getExpire(LOCK_KEY, TimeUnit.SECONDS));
                return null;
            }).given(context.getBean(ConversationService.class)).closeIdleConversations(any());

            context.getBean(ConversationClosingScheduler.class).closeIdleConversations();

            assertThat(ttlSecondsWhileClosing).singleElement(InstanceOfAssertFactories.LONG)
                .isGreaterThan(LOCK_AT_LEAST_FOR.toSeconds())
                .isLessThanOrEqualTo(LOCK_AT_MOST_FOR.toSeconds());
        });
    }

    @Test
    @DisplayName("락이 만료되어 다른 서버가 잡았다면 종료 처리가 끝나도 그 락을 바꾸지 않는다")
    void keepsOtherServerLockAfterClosing() {
        contextRunnerWithRedis(REDIS_CONTAINER.getMappedPort(6379)).run(context -> {
            willAnswer(invocation -> {
                stringRedisTemplate.opsForValue().set(LOCK_KEY, OTHER_SERVER_LOCK);
                return null;
            }).given(context.getBean(ConversationService.class)).closeIdleConversations(any());

            context.getBean(ConversationClosingScheduler.class).closeIdleConversations();

            assertThat(stringRedisTemplate.opsForValue().get(LOCK_KEY)).isEqualTo(OTHER_SERVER_LOCK);
        });
    }

    @Test
    @DisplayName("Redis에 연결할 수 없으면 이번 회차의 대화방 종료 처리를 건너뛴다")
    void skipsClosingWhenRedisIsUnavailable() throws Exception {
        contextRunnerWithRedis(unavailablePort()).run(context -> {
            ConversationClosingScheduler scheduler = context.getBean(ConversationClosingScheduler.class);

            assertThatThrownBy(scheduler::closeIdleConversations).isInstanceOf(DataAccessException.class);

            then(context.getBean(ConversationService.class)).should(never()).closeIdleConversations(any());
        });
    }

    private static ApplicationContextRunner contextRunnerWithRedis(int port) {
        return new ApplicationContextRunner()
            .withPropertyValues("app.conversation.closing-scheduler.enabled=true")
            .withBean(RedisConnectionFactory.class, () -> connectionFactory(port))
            .withBean(ConversationService.class, () -> mock(ConversationService.class))
            .withUserConfiguration(SchedulerLockConfig.class, ConversationClosingScheduler.class);
    }

    private static LettuceConnectionFactory connectionFactory(int port) {
        return new LettuceConnectionFactory(REDIS_CONTAINER.getHost(), port);
    }

    private static int unavailablePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
