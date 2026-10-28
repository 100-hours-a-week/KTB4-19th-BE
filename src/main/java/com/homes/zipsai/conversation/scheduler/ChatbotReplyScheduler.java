package com.homes.zipsai.conversation.scheduler;

import java.util.concurrent.TimeUnit;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.homes.zipsai.conversation.outbox.ChatbotReplyWorker;

import lombok.RequiredArgsConstructor;

@Component
@ConditionalOnProperty(name = "app.conversation.reply-worker.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class ChatbotReplyScheduler {

    private final ChatbotReplyWorker worker;

    @Scheduled(fixedDelay = 5, timeUnit = TimeUnit.SECONDS)
    public void retryReplies() {
        worker.processBatch();
    }
}
