package com.homes.zipsai.conversation.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.homes.zipsai.conversation.service.ConversationService;

class ConversationClosingSchedulerTest {

    private static final String ENABLED = "app.conversation.closing-scheduler.enabled";

    ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withBean(ConversationService.class, () -> mock(ConversationService.class))
        .withUserConfiguration(ConversationClosingScheduler.class);

    @Test
    @DisplayName("설정을 끄면 대화방 종료 스케줄러를 등록하지 않는다")
    void skipsSchedulerWhenDisabled() {
        contextRunner.withPropertyValues(ENABLED + "=false")
            .run(context -> assertThat(context).doesNotHaveBean(ConversationClosingScheduler.class));
    }

    @Test
    @DisplayName("설정을 켜면 대화방 종료 스케줄러를 등록한다")
    void registersSchedulerWhenEnabled() {
        contextRunner.withPropertyValues(ENABLED + "=true")
            .run(context -> assertThat(context).hasSingleBean(ConversationClosingScheduler.class));
    }

    @Test
    @DisplayName("설정이 없으면 대화방 종료 스케줄러를 등록하지 않는다")
    void skipsSchedulerWhenPropertyIsMissing() {
        contextRunner.run(context -> assertThat(context).doesNotHaveBean(ConversationClosingScheduler.class));
    }
}
