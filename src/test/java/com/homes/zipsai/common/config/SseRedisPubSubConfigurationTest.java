package com.homes.zipsai.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import com.homes.zipsai.global.logging.StructuredLogger;

@DisplayName("SSE Redis Pub/Sub 수신 실행기")
class SseRedisPubSubConfigurationTest {

    @Test
    @DisplayName("수신 처리 지연으로 대기열이 가득 차면 새 메시지를 버리고 구독 스레드를 막지 않는다")
    void dropsMessagesWhenQueueIsFull() throws Exception {
        StructuredLogger structuredLogger = mock(StructuredLogger.class);
        ThreadPoolTaskExecutor messageExecutor = new SseRedisPubSubConfiguration(
            new RedisPubSubProperties("test:notification-events"), structuredLogger).sseRedisMessageExecutor();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean overflowRan = new AtomicBoolean();
        messageExecutor.initialize();
        try {
            messageExecutor.execute(() -> {
                started.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            });

            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

            for (int queuedMessageCount = 0; queuedMessageCount < 256; queuedMessageCount++) {
                messageExecutor.execute(() -> release.getCount());
            }

            assertThat(messageExecutor.getThreadPoolExecutor().getQueue().remainingCapacity()).isZero();

            assertThatCode(() -> messageExecutor.execute(() -> overflowRan.set(true))).doesNotThrowAnyException();

            assertThat(overflowRan).isFalse();

            verify(structuredLogger).pubSubFailed(eq("queue"), eq("test:notification-events"), isNull(),
                any(RejectedExecutionException.class));
        } finally {
            release.countDown();
            messageExecutor.shutdown();
        }
    }
}
