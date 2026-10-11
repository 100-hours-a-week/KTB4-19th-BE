package com.homes.zipsai.global.config;

import java.time.Duration;
import java.util.Map;

import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;

@Configuration
@EnableAsync
public class AsyncConfig {

    public static final String CHATBOT_REPLY_EXECUTOR = "chatbotReplyExecutor";

    private static final Duration TASK_TERMINATION_TIMEOUT = Duration.ofSeconds(60);

    @Bean(name = CHATBOT_REPLY_EXECUTOR, defaultCandidate = false)
    public TaskExecutor chatbotReplyExecutor() {
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("chatbot-reply-");
        executor.setVirtualThreads(true);
        executor.setTaskDecorator(AsyncConfig::withCallerMdc);
        executor.setTaskTerminationTimeout(TASK_TERMINATION_TIMEOUT.toMillis());
        return executor;
    }

    private static Runnable withCallerMdc(Runnable task) {
        Map<String, String> callerMdc = MDC.getCopyOfContextMap();
        return () -> {
            if (callerMdc != null) {
                MDC.setContextMap(callerMdc);
            }
            try {
                task.run();
            } finally {
                MDC.clear();
            }
        };
    }
}
