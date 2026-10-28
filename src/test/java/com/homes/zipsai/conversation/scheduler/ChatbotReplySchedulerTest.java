package com.homes.zipsai.conversation.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.config.IntervalTask;
import org.springframework.scheduling.config.ScheduledTask;

import com.homes.zipsai.conversation.outbox.ChatbotReplyWorker;

class ChatbotReplySchedulerTest {

    private static final String ENABLED = "app.conversation.reply-worker.enabled";

    ChatbotReplyWorker worker = mock(ChatbotReplyWorker.class);

    TaskScheduler taskScheduler = mock(TaskScheduler.class);

    ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withBean(ChatbotReplyWorker.class, () -> worker)
        .withBean(TaskScheduler.class, () -> taskScheduler)
        .withUserConfiguration(TestSchedulingConfiguration.class, ChatbotReplyScheduler.class);

    @Test
    @DisplayName("기본 설정에서 5초 주기의 작업을 등록하고 실행 시 워커를 한 번 호출한다")
    void registersFiveSecondScheduleAndInvokesWorker() {
        given(taskScheduler.getClock()).willReturn(Clock.systemUTC());

        contextRunner.run(context -> {
            ScheduledAnnotationBeanPostProcessor scheduler =
                context.getBean(ScheduledAnnotationBeanPostProcessor.class);
            assertThat(scheduler.getScheduledTasks()).extracting(ScheduledTask::getTask).singleElement()
                .isInstanceOfSatisfying(IntervalTask.class, task -> {
                    assertThat(task.getIntervalDuration()).isEqualTo(Duration.ofSeconds(5));

                    task.getRunnable().run();

                    then(worker).should().processBatch();
                });
        });
    }

    @Test
    @DisplayName("설정을 끄면 자동 스캔 작업을 등록하지 않는다")
    void skipsAutomaticScanWhenDisabled() {
        contextRunner.withPropertyValues(ENABLED + "=false").run(context -> {
            ScheduledAnnotationBeanPostProcessor scheduler =
                context.getBean(ScheduledAnnotationBeanPostProcessor.class);
            assertThat(scheduler.getScheduledTasks()).isEmpty();
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    static class TestSchedulingConfiguration {
    }
}
