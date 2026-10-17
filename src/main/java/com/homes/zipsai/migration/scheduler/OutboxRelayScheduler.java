package com.homes.zipsai.migration.scheduler;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.homes.zipsai.global.util.TimeUtils;
import com.homes.zipsai.migration.service.OutboxRelayService;
import com.homes.zipsai.migration.service.OutboxWorkerMetrics;

import lombok.RequiredArgsConstructor;

@Component
@ConditionalOnBooleanProperty("app.migration.worker-enabled")
@RequiredArgsConstructor
public class OutboxRelayScheduler {

    private static final Duration RELAY_TIME_LIMIT = Duration.ofSeconds(10);

    private final OutboxRelayService outboxRelayService;
    private final OutboxWorkerMetrics outboxWorkerMetrics;

    @Scheduled(fixedDelay = 1, timeUnit = TimeUnit.SECONDS, scheduler = "migrationTaskScheduler")
    public void relayPendingEvents() {
        long deadline = System.nanoTime() + RELAY_TIME_LIMIT.toNanos();
        boolean hasMoreEvents;
        do {
            hasMoreEvents = outboxRelayService.relayPendingEvents(TimeUtils.now());
        } while (hasMoreEvents && System.nanoTime() < deadline);
    }

    @Scheduled(fixedDelay = 1, timeUnit = TimeUnit.MINUTES, scheduler = "migrationTaskScheduler")
    public void logUnprocessedCounts() {
        outboxRelayService.logUnprocessedCounts();
    }

    @Scheduled(fixedDelay = 15, timeUnit = TimeUnit.SECONDS, scheduler = "migrationTaskScheduler")
    public void refreshMetrics() {
        outboxWorkerMetrics.refresh(TimeUtils.now());
    }
}
