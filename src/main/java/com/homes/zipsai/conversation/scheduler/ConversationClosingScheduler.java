package com.homes.zipsai.conversation.scheduler;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.homes.zipsai.conversation.service.ConversationService;

import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

@Component
@ConditionalOnBooleanProperty("app.conversation.closing-scheduler.enabled")
@RequiredArgsConstructor
public class ConversationClosingScheduler {

    private final ConversationService conversationService;

    @Scheduled(fixedDelay = 5, timeUnit = TimeUnit.MINUTES)
    @SchedulerLock(name = "closeIdleConversations", lockAtLeastFor = "5m", lockAtMostFor = "10m")
    public void closeIdleConversations() {
        conversationService.closeIdleConversations(LocalDateTime.now());
    }
}
