package com.homes.zipsai.conversation.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

class ConversationReplyLockTransferTest {

    private static final long CONVERSATION_ID = 10L;
    private static final String LOCK_KEY = ConversationReplyLock.KEY_PREFIX + CONVERSATION_ID;
    private static final Duration TTL = Duration.ofSeconds(150);
    private static final RedisScript<Long> EXPIRATION_TIME =
        RedisScript.of("return redis.call('PEXPIRETIME', KEYS[1])", Long.class);
    private static final GenericContainer<?> REDIS =
        new GenericContainer<>(DockerImageName.parse("redis:8.2.10-alpine")).withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redis;

    private ConversationReplyLock lock;

    @BeforeAll
    static void startRedis() {
        REDIS.start();
        connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        redis = new StringRedisTemplate(connectionFactory);
    }

    @AfterAll
    static void stopRedis() {
        connectionFactory.destroy();
        REDIS.stop();
    }

    @BeforeEach
    void setUp() {
        redis.delete(LOCK_KEY);
        lock = new ConversationReplyLock(redis, new ConversationReplyLockProperties(TTL));
    }

    @Test
    @DisplayName("일치하는 이전 토큰을 새 토큰과 지정한 만료 시각으로 교체한다")
    void replacesMatchingTokenWithBoundedTtl() {
        String previousToken = lock.acquire(CONVERSATION_ID);
        Instant expiresAt = Instant.now().plusSeconds(20);

        boolean transferred = lock.transfer(CONVERSATION_ID, previousToken, "new-token", expiresAt);

        assertThat(transferred).isTrue();

        assertThat(redis.opsForValue().get(LOCK_KEY)).isEqualTo("new-token");

        assertThat(redis.execute(EXPIRATION_TIME, List.of(LOCK_KEY))).isEqualTo(expiresAt.toEpochMilli());
    }

    @Test
    @DisplayName("다른 실행의 토큰과 TTL은 교체하거나 연장하지 않는다")
    void preservesAnotherOwnersTokenAndTtl() {
        redis.opsForValue().set(LOCK_KEY, "other-token", Duration.ofSeconds(20));
        Long remaining = redis.getExpire(LOCK_KEY, TimeUnit.MILLISECONDS);

        boolean transferred = lock.transfer(CONVERSATION_ID, "old-token", "new-token", Instant.now().plus(TTL));

        assertThat(transferred).isFalse();

        assertThat(redis.opsForValue().get(LOCK_KEY)).isEqualTo("other-token");

        assertThat(redis.getExpire(LOCK_KEY, TimeUnit.MILLISECONDS)).isPositive().isLessThanOrEqualTo(remaining);
    }

    @Test
    @DisplayName("키가 비어 있으면 새 토큰과 지정한 만료 시각으로 입력 차단을 다시 획득한다")
    void acquiresMissingKeyWithNewToken() {
        Instant expiresAt = Instant.now().plusSeconds(30);

        boolean transferred = lock.transfer(CONVERSATION_ID, "expired-token", "new-token", expiresAt);

        assertThat(transferred).isTrue();

        assertThat(redis.opsForValue().get(LOCK_KEY)).isEqualTo("new-token");

        assertThat(redis.execute(EXPIRATION_TIME, List.of(LOCK_KEY))).isEqualTo(expiresAt.toEpochMilli());
    }

    @Test
    @DisplayName("동시에 같은 이전 토큰을 인계하려 해도 한 실행만 새 토큰을 반영한다")
    void transfersToOnlyOneConcurrentOwner() throws Exception {
        String previousToken = lock.acquire(CONVERSATION_ID);
        Instant expiresAt = Instant.now().plus(TTL);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Boolean> first = executor.submit(() -> {
                start.await();
                return lock.transfer(CONVERSATION_ID, previousToken, "first-token", expiresAt);
            });
            Future<Boolean> second = executor.submit(() -> {
                start.await();
                return lock.transfer(CONVERSATION_ID, previousToken, "second-token", expiresAt);
            });
            start.countDown();
            boolean firstWon = first.get(5, TimeUnit.SECONDS);
            boolean secondWon = second.get(5, TimeUnit.SECONDS);

            assertThat(List.of(firstWon, secondWon)).containsExactlyInAnyOrder(true, false);

            assertThat(redis.opsForValue().get(LOCK_KEY)).isEqualTo(firstWon ? "first-token" : "second-token");
        }
    }
}
