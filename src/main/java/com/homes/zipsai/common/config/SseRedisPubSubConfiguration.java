package com.homes.zipsai.common.config;

import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import com.homes.zipsai.common.service.NotificationEventSubscriber;
import com.homes.zipsai.global.logging.StructuredLogger;

import lombok.RequiredArgsConstructor;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RedisPubSubProperties.class)
@RequiredArgsConstructor
public class SseRedisPubSubConfiguration {

    private final RedisPubSubProperties redisPubSubProperties;
    private final StructuredLogger structuredLogger;

    @Bean(defaultCandidate = false, destroyMethod = "shutdownNow")
    ScheduledExecutorService sseRedisSubscriptionExecutor() {
        return Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("sse-pubsub-subscription-", 0).factory());
    }

    @Bean(defaultCandidate = false)
    ThreadPoolTaskExecutor sseRedisMessageExecutor() {
        ThreadPoolTaskExecutor messageExecutor = new ThreadPoolTaskExecutor();
        messageExecutor.setCorePoolSize(1);
        messageExecutor.setMaxPoolSize(1);
        messageExecutor.setQueueCapacity(256);
        messageExecutor.setThreadNamePrefix("sse-pubsub-message-");
        messageExecutor.setRejectedExecutionHandler((messageTask, messageThreadPool) ->
            structuredLogger.pubSubFailed("queue", redisPubSubProperties.notificationChannel(), null,
                new RejectedExecutionException()));
        return messageExecutor;
    }

    @Bean
    RedisMessageListenerContainer redisMessageListenerContainer(RedisConnectionFactory redisConnectionFactory,
        NotificationEventSubscriber notificationEventSubscriber,
        @Qualifier("sseRedisMessageExecutor") ThreadPoolTaskExecutor messageExecutor,
        @Qualifier("sseRedisSubscriptionExecutor") ScheduledExecutorService subscriptionExecutor) {
        RedisMessageListenerContainer redisMessageListenerContainer = new RedisMessageListenerContainer();
        redisMessageListenerContainer.setAutoStartup(false);
        redisMessageListenerContainer.setConnectionFactory(redisConnectionFactory);
        redisMessageListenerContainer.setTaskExecutor(messageExecutor);
        redisMessageListenerContainer.setSubscriptionExecutor(subscriptionExecutor);
        redisMessageListenerContainer.addMessageListener(notificationEventSubscriber,
            new ChannelTopic(redisPubSubProperties.notificationChannel()));
        redisMessageListenerContainer.setErrorHandler(exception ->
            structuredLogger.pubSubFailed("dispatch", redisPubSubProperties.notificationChannel(), null, exception));
        return redisMessageListenerContainer;
    }

    @Bean
    ApplicationListener<ApplicationReadyEvent> sseRedisSubscriptionStarter(
        RedisMessageListenerContainer redisMessageListenerContainer,
        @Qualifier("sseRedisSubscriptionExecutor") ScheduledExecutorService subscriptionExecutor) {
        return applicationReadyEvent -> subscriptionExecutor.execute(() ->
            startSubscription(redisMessageListenerContainer, subscriptionExecutor));
    }

    private void startSubscription(RedisMessageListenerContainer redisMessageListenerContainer,
        ScheduledExecutorService subscriptionExecutor) {
        if (!redisMessageListenerContainer.isActive()) {
            return;
        }
        try {
            redisMessageListenerContainer.start();
        } catch (RuntimeException exception) {
            structuredLogger.pubSubFailed("subscribe", redisPubSubProperties.notificationChannel(), null, exception);
            if (exception.getCause() instanceof RedisConnectionFailureException
                && redisMessageListenerContainer.isActive()) {
                redisMessageListenerContainer.stop();
                subscriptionExecutor.schedule(() ->
                    startSubscription(redisMessageListenerContainer, subscriptionExecutor),
                    RedisMessageListenerContainer.DEFAULT_RECOVERY_INTERVAL, TimeUnit.MILLISECONDS);
            }
        }
    }
}
