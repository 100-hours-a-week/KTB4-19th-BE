package com.homes.zipsai.conversation.consumer;

import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.context.support.DefaultLifecycleProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import com.homes.zipsai.conversation.outbox.ChatbotReplyWorkerProperties;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.conversation.reply-consumer.enabled", havingValue = "true")
@EnableConfigurationProperties(ChatbotReplyWorkerProperties.class)
public class ChatbotReplyConsumerConfiguration {

    @Bean
    SimpleMessageListenerContainer chatbotReplyRequestContainer(ConnectionFactory connectionFactory,
            ChatbotReplyRequestListener listener, ChatbotReplyWorkerProperties workerProperties,
            DefaultLifecycleProcessor lifecycleProcessor,
            @Value("${app.conversation.reply-consumer.queue}") String queue) {
        SimpleMessageListenerContainer container = new SimpleMessageListenerContainer(connectionFactory);
        container.setQueueNames(queue);
        container.setMessageListener(listener);
        container.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        long shutdownTimeout = workerProperties.requestTimeout().toMillis();
        container.setShutdownTimeout(shutdownTimeout);
        lifecycleProcessor.setTimeoutForShutdownPhase(container.getPhase(), shutdownTimeout);
        return container;
    }

    @EventListener(ContextClosedEvent.class)
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public void stopReceiving(ContextClosedEvent event) {
        event.getApplicationContext().getBean("chatbotReplyRequestContainer", SimpleMessageListenerContainer.class)
            .stop();
    }
}
