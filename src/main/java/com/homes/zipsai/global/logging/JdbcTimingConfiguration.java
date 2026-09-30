package com.homes.zipsai.global.logging;

import javax.sql.DataSource;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class JdbcTimingConfiguration {
    @Bean
    static BeanPostProcessor jdbcTimingDataSourcePostProcessor() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof DataSource dataSource && !(bean instanceof JdbcTimingDataSource)) {
                    return new JdbcTimingDataSource(dataSource);
                }
                return bean;
            }
        };
    }
}
