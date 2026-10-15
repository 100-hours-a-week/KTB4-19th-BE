package com.homes.zipsai.conversation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.ServerSocket;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import com.homes.zipsai.global.exception.ConflictException;

class ConversationReplyLockTest {

    private static final long CONVERSATION_ID = 10L;
    private static final String LOCK_KEY = ConversationReplyLock.KEY_PREFIX + CONVERSATION_ID;
    private static final Duration TTL = Duration.ofSeconds(150);
    private static final String OTHER_REQUEST_TOKEN = "other-request-token";
    private static final GenericContainer<?> REDIS_CONTAINER =
        new GenericContainer<>(DockerImageName.parse("redis:8.2.10-alpine")).withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate stringRedisTemplate;

    ConversationReplyLock conversationReplyLock;

    @BeforeAll
    static void startRedis() {
        REDIS_CONTAINER.start();
        connectionFactory = startedConnectionFactory(REDIS_CONTAINER.getMappedPort(6379));
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
        conversationReplyLock =
            new ConversationReplyLock(stringRedisTemplate, new ConversationReplyLockProperties(TTL));
    }

    @Test
    @DisplayName("잠금을 잡으면 토큰을 값으로 저장하고 TTL을 건다")
    void storesTokenWithTtl() {
        String token = conversationReplyLock.acquire(CONVERSATION_ID);

        assertThat(stringRedisTemplate.opsForValue().get(LOCK_KEY)).isEqualTo(token);
        assertThat(stringRedisTemplate.getExpire(LOCK_KEY, TimeUnit.SECONDS)).isPositive()
            .isLessThanOrEqualTo(TTL.toSeconds());
    }

    @Test
    @DisplayName("답변 중인 대화방의 잠금은 다시 잡을 수 없다")
    void rejectsAcquireWhileLocked() {
        conversationReplyLock.acquire(CONVERSATION_ID);

        assertThatThrownBy(() -> conversationReplyLock.acquire(CONVERSATION_ID))
            .isInstanceOf(ConflictException.class)
            .hasFieldOrPropertyWithValue("code", "CONVERSATION_BUSY");
    }

    @Test
    @DisplayName("자신의 토큰으로 잠금을 풀면 키를 지운다")
    void releasesOwnLock() {
        String token = conversationReplyLock.acquire(CONVERSATION_ID);

        conversationReplyLock.release(CONVERSATION_ID, token);

        assertThat(stringRedisTemplate.hasKey(LOCK_KEY)).isFalse();
    }

    @Test
    @DisplayName("TTL이 지나 다른 요청이 잡은 잠금은 이전 요청의 토큰으로 풀 수 없다")
    void keepsLockOfAnotherRequest() {
        String expiredToken = conversationReplyLock.acquire(CONVERSATION_ID);
        stringRedisTemplate.opsForValue().set(LOCK_KEY, OTHER_REQUEST_TOKEN, TTL);

        conversationReplyLock.release(CONVERSATION_ID, expiredToken);

        assertThat(stringRedisTemplate.opsForValue().get(LOCK_KEY)).isEqualTo(OTHER_REQUEST_TOKEN);
    }

    @Test
    @DisplayName("Redis에 연결할 수 없으면 잠금을 잡지 못하고 예외를 던진다")
    void throwsWhenRedisIsUnavailableOnAcquire() throws Exception {
        LettuceConnectionFactory unavailableConnectionFactory = startedConnectionFactory(unavailablePort());
        ConversationReplyLock unavailableLock =
            new ConversationReplyLock(new StringRedisTemplate(unavailableConnectionFactory),
                new ConversationReplyLockProperties(TTL));

        assertThatThrownBy(() -> unavailableLock.acquire(CONVERSATION_ID))
            .isInstanceOf(DataAccessException.class);
        unavailableConnectionFactory.destroy();
    }

    @Test
    @DisplayName("Redis에 연결할 수 없어 잠금을 풀지 못해도 예외를 던지지 않는다")
    void ignoresReleaseFailureWhenRedisIsUnavailable() throws Exception {
        LettuceConnectionFactory unavailableConnectionFactory = startedConnectionFactory(unavailablePort());
        ConversationReplyLock unavailableLock =
            new ConversationReplyLock(new StringRedisTemplate(unavailableConnectionFactory),
                new ConversationReplyLockProperties(TTL));

        assertThatNoException().isThrownBy(() -> unavailableLock.release(CONVERSATION_ID, "token"));
        unavailableConnectionFactory.destroy();
    }

    private static LettuceConnectionFactory startedConnectionFactory(int port) {
        LettuceConnectionFactory factory = new LettuceConnectionFactory(REDIS_CONTAINER.getHost(), port);
        factory.afterPropertiesSet();
        factory.start();
        return factory;
    }

    private static int unavailablePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
