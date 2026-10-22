package com.homes.zipsai.conversation.service;

import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class ChatbotReplyShutdownSignal {

    private volatile boolean shuttingDown;

    @EventListener(ContextClosedEvent.class)
    public void startShuttingDown() {
        shuttingDown = true;
    }

    public boolean isShuttingDown() {
        return shuttingDown;
    }
}
