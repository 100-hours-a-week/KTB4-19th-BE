package com.homes.zipsai.global.config;

import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskDecorator;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;

import com.homes.zipsai.global.logging.StructuredLogger;
import com.homes.zipsai.global.logging.TaskLoggingDecorator;

@Configuration
@EnableAsync
public class AsyncConfig {

    public static final String CHATBOT_REPLY_EXECUTOR = "chatbotReplyExecutor";

    private static final String CHATBOT_REPLY_STAGE = "chatbot_reply";
    private static final String ASYNC_TASK_STAGE = "async_task";
    private static final Duration TASK_TERMINATION_TIMEOUT = Duration.ofSeconds(60);

    @Bean
    public TaskDecorator taskLoggingDecorator(StructuredLogger structuredLogger) {
        return new TaskLoggingDecorator(structuredLogger, ASYNC_TASK_STAGE);
    }

    @Bean(name = CHATBOT_REPLY_EXECUTOR, defaultCandidate = false)
    public TaskExecutor chatbotReplyExecutor(StructuredLogger structuredLogger) {
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("chatbot-reply-");
        executor.setVirtualThreads(true);
        executor.setTaskDecorator(new TaskLoggingDecorator(structuredLogger, CHATBOT_REPLY_STAGE));
        executor.setTaskTerminationTimeout(TASK_TERMINATION_TIMEOUT.toMillis());
        return executor;
    }
}
