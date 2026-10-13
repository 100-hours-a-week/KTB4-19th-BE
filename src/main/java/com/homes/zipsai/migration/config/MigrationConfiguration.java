package com.homes.zipsai.migration.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(MigrationProperties.class)
public class MigrationConfiguration {
}
