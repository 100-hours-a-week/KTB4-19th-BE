package com.homes.zipsai.conversation.scheduler;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.homes.zipsai.conversation.service.ConversationService;

import lombok.RequiredArgsConstructor;

@Component
@ConditionalOnProperty(name = "app.conversation.closing-scheduler.enabled", havingValue = "true",
    matchIfMissing = true)
@RequiredArgsConstructor
public class ConversationClosingScheduler {

    private final ConversationService conversationService;

    @Scheduled(fixedDelay = 5, timeUnit = TimeUnit.MINUTES)
    public void closeIdleConversations() {
        conversationService.closeIdleConversations(LocalDateTime.now());
    }
}
