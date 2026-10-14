package com.homes.zipsai.conversation.scheduler;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.homes.zipsai.conversation.service.ConversationService;

import lombok.RequiredArgsConstructor;

@Component
@ConditionalOnBooleanProperty("app.conversation.closing-scheduler.enabled")
@RequiredArgsConstructor
public class ConversationClosingScheduler {

    private final ConversationService conversationService;

    @Scheduled(fixedDelay = 5, timeUnit = TimeUnit.MINUTES)
    public void closeIdleConversations() {
        conversationService.closeIdleConversations(LocalDateTime.now());
    }
}
