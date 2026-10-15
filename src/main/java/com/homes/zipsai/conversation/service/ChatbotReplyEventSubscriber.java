package com.homes.zipsai.conversation.service;

import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import com.homes.zipsai.common.service.RedisMessageSubscriber;
import com.homes.zipsai.common.service.SseService;
import com.homes.zipsai.conversation.config.ChatbotReplyPubSubProperties;
import com.homes.zipsai.conversation.event.ChatbotReplyEvent;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class ChatbotReplyEventSubscriber implements MessageListener {

    private final RedisMessageSubscriber redisMessageSubscriber;
    private final SseService sseService;
    private final ChatbotReplyPubSubProperties chatbotReplyPubSubProperties;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        redisMessageSubscriber.receive(message, chatbotReplyPubSubProperties.chatbotReplyChannel(),
            ChatbotReplyEvent.class, event -> sseService.send(event.userId(), event.toSseEvent()));
    }
}
