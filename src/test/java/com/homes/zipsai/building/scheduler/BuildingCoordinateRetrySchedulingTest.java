package com.homes.zipsai.building.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;

import com.homes.zipsai.building.service.BuildingCoordinateRetryWorker;
import com.homes.zipsai.building.service.BuildingCoordinateRetryWorker.Outcome;
import com.homes.zipsai.global.config.SchedulingConfig;

class BuildingCoordinateRetrySchedulingTest {

    private final BuildingCoordinateRetryWorker buildingCoordinateRetryWorker =
        mock(BuildingCoordinateRetryWorker.class);
    private final ApplicationContextRunner applicationContextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration.class))
        .withBean(BuildingCoordinateRetryWorker.class, () -> buildingCoordinateRetryWorker)
        .withUserConfiguration(SchedulingConfig.class, BuildingCoordinateRetryScheduler.class);

    @Test
    @DisplayName("시작 직후 실행하지 않고 매시 정각 실행을 전용 스케줄러에 등록한다")
    void schedulesHourlyWithoutStartupExecution() {
        applicationContextRunner.run(context -> {
            assertThat(context).hasNotFailed();

            Set<ScheduledTask> tasks = context.getBean(ScheduledTaskHolder.class).getScheduledTasks();
            assertThat(tasks).hasSize(1);

            ScheduledTask task = tasks.iterator().next();
            assertThat(task.getTask()).isInstanceOf(CronTask.class);

            assertThat(((CronTask) task.getTask()).getExpression()).isEqualTo("0 0 * * * *");

            Instant nextExecution = task.nextExecution();
            assertThat(nextExecution).isNotNull();

            assertThat(Duration.between(Instant.now(), nextExecution))
                .isPositive().isLessThanOrEqualTo(Duration.ofHours(1));

            ThreadPoolTaskScheduler coordinateTaskScheduler = context.getBean(
                "buildingCoordinateRetryTaskScheduler", ThreadPoolTaskScheduler.class);
            assertThat(coordinateTaskScheduler.getScheduledThreadPoolExecutor().getQueue()).hasSize(1);

            verifyNoInteractions(buildingCoordinateRetryWorker);
        });
    }

    @Test
    @DisplayName("좌표 조회가 대기 중이어도 기본 스케줄러의 정기 작업은 계속 실행된다")
    void isolatesCoordinateWorkFromDefaultScheduledTasks() {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(buildingCoordinateRetryWorker.executeNext()).thenAnswer(invocation -> {
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("실행 해제 시간 초과");
            }
            return Outcome.NO_WORK;
        });
        applicationContextRunner.withBean(DefaultScheduledProbe.class).run(context -> {
            assertThat(context).hasNotFailed();

            ThreadPoolTaskScheduler coordinateTaskScheduler = context.getBean(
                "buildingCoordinateRetryTaskScheduler", ThreadPoolTaskScheduler.class);
            ThreadPoolTaskScheduler defaultTaskScheduler =
                context.getBean("taskScheduler", ThreadPoolTaskScheduler.class);
            assertThat(coordinateTaskScheduler).isNotSameAs(defaultTaskScheduler);

            coordinateTaskScheduler.schedule(context.getBean(BuildingCoordinateRetryScheduler.class)::retryCoordinates,
                Instant.now());
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

                CountDownLatch defaultTaskProgress = new CountDownLatch(1);
                context.getBean(DefaultScheduledProbe.class).nextExecution.set(defaultTaskProgress);

                assertThat(defaultTaskProgress.await(2, TimeUnit.SECONDS)).isTrue();
            } finally {
                release.countDown();
            }
        });
    }

    @Test
    @DisplayName("테스트용 비활성 cron이면 정기 실행을 등록하지 않는다")
    void supportsDisablingAutomaticExecutionInTests() {
        applicationContextRunner.withPropertyValues("app.building.coordinate-retry.cron=-").run(context -> {
            assertThat(context).hasNotFailed();

            assertThat(context.getBean(ScheduledTaskHolder.class).getScheduledTasks()).isEmpty();

            verifyNoInteractions(buildingCoordinateRetryWorker);
        });
    }

    static class DefaultScheduledProbe {

        private final AtomicReference<CountDownLatch> nextExecution = new AtomicReference<>(new CountDownLatch(1));

        @Scheduled(fixedDelay = 10)
        public void run() {
            nextExecution.get().countDown();
        }
    }
}
