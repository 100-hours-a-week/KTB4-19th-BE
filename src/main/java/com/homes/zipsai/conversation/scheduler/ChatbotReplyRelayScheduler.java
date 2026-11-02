package com.homes.zipsai.conversation.scheduler;

import java.util.concurrent.TimeUnit;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.homes.zipsai.conversation.outbox.ChatbotReplyRelayWorker;

import lombok.RequiredArgsConstructor;

@Component
@ConditionalOnProperty(name = "app.conversation.reply-relay.enabled", havingValue = "true")
@RequiredArgsConstructor
public class ChatbotReplyRelayScheduler {

    private final ChatbotReplyRelayWorker worker;

    @Scheduled(fixedDelay = 5, timeUnit = TimeUnit.SECONDS)
    public void relayReplies() {
        worker.processBatch();
    }
}
