package com.homes.zipsai.global.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.homes.zipsai.common.config.SseProperties;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(SseProperties.class)
public class SchedulingConfig {
}
