package com.homes.zipsai.conversation.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import com.homes.zipsai.conversation.service.ChatbotReplyEventSubscriber;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ChatbotReplyPubSubProperties.class)
public class ChatbotReplyPubSubConfiguration {

    public ChatbotReplyPubSubConfiguration(RedisMessageListenerContainer redisMessageListenerContainer,
                                           ChatbotReplyEventSubscriber chatbotReplyEventSubscriber,
                                           ChatbotReplyPubSubProperties chatbotReplyPubSubProperties) {
        redisMessageListenerContainer.addMessageListener(chatbotReplyEventSubscriber,
            new ChannelTopic(chatbotReplyPubSubProperties.chatbotReplyChannel()));
    }
}
