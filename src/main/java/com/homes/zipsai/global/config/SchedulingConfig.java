package com.homes.zipsai.global.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import com.homes.zipsai.common.config.SseProperties;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(SseProperties.class)
public class SchedulingConfig {

    @Bean(defaultCandidate = false)
    ThreadPoolTaskScheduler buildingCoordinateRetryTaskScheduler() {
        ThreadPoolTaskScheduler taskScheduler = new ThreadPoolTaskScheduler();
        taskScheduler.setPoolSize(1);
        taskScheduler.setThreadNamePrefix("building-coordinate-retry-");
        return taskScheduler;
    }
}
