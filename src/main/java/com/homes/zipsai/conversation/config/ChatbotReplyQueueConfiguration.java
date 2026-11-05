package com.homes.zipsai.conversation.config;

import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.homes.zipsai.conversation.outbox.ChatbotReplyRelayProperties;

@Configuration
@EnableConfigurationProperties(ChatbotReplyQueueProperties.class)
public class ChatbotReplyQueueConfiguration {

    public static final String DEAD_LETTER_EXCHANGE = "chatbot.reply.dlx";
    public static final String DEAD_LETTER_QUEUE = "chatbot.reply.request.dlq";
    public static final int DELIVERY_LIMIT = 2;
    public static final String DELAYED_RETRY_TYPE = "all";

    @Bean
    Declarables chatbotReplyQueues(ChatbotReplyRelayProperties relayProperties,
                                   ChatbotReplyQueueProperties queueProperties) {
        DirectExchange requestExchange = new DirectExchange(relayProperties.exchange());
        Queue requestQueue = QueueBuilder.durable(relayProperties.routingKey())
            .quorum()
            .deliveryLimit(DELIVERY_LIMIT)
            .withArgument("x-delayed-retry-type", DELAYED_RETRY_TYPE)
            .withArgument("x-delayed-retry-min", queueProperties.delayedRetryMin().toMillis())
            .withArgument("x-delayed-retry-max", queueProperties.delayedRetryMax().toMillis())
            .deadLetterExchange(DEAD_LETTER_EXCHANGE)
            .deadLetterRoutingKey(DEAD_LETTER_QUEUE)
            .build();
        DirectExchange deadLetterExchange = new DirectExchange(DEAD_LETTER_EXCHANGE);
        Queue deadLetterQueue = QueueBuilder.durable(DEAD_LETTER_QUEUE).quorum().build();

        return new Declarables(requestExchange, requestQueue,
            BindingBuilder.bind(requestQueue).to(requestExchange).with(relayProperties.routingKey()),
            deadLetterExchange, deadLetterQueue,
            BindingBuilder.bind(deadLetterQueue).to(deadLetterExchange).with(DEAD_LETTER_QUEUE));
    }
}
