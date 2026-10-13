package com.homes.zipsai.migration.config;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.SimpleAsyncTaskScheduler;

import com.homes.zipsai.migration.service.MigrationRouting;
import com.zaxxer.hikari.HikariDataSource;

@Configuration(proxyBeanMethods = false)
@ConditionalOnMigrationEnabled
public class MigrationRoutingConfiguration {

    private static final String HIKARI_PROPERTIES = "spring.datasource.hikari";
    private static final int SHADOW_READ_CONCURRENCY = 4;

    @Bean(defaultCandidate = false)
    MigrationDataSources migrationDataSources(DataSourceProperties dataSourceProperties,
                                              MigrationProperties migrationProperties, Environment environment) {
        MigrationProperties.V2Datasource v2Datasource = migrationProperties.v2Datasource();
        HikariDataSource v1DataSource = dataSourceProperties.initializeDataSourceBuilder()
            .type(HikariDataSource.class)
            .build();
        HikariDataSource v2DataSource = DataSourceBuilder.create()
            .type(HikariDataSource.class)
            .driverClassName(dataSourceProperties.determineDriverClassName())
            .url(v2Datasource.url())
            .username(v2Datasource.username())
            .password(v2Datasource.password())
            .build();
        Binder binder = Binder.get(environment);
        binder.bind(HIKARI_PROPERTIES, Bindable.ofInstance(v1DataSource));
        binder.bind(HIKARI_PROPERTIES, Bindable.ofInstance(v2DataSource));
        v1DataSource.setPoolName("v1");
        v2DataSource.setPoolName("v2");
        return new MigrationDataSources(v1DataSource, v2DataSource);
    }

    @Bean
    @Primary
    @DependsOn("migrationSettingsPoller")
    DataSource dataSource(@Qualifier("migrationDataSources") MigrationDataSources migrationDataSources,
                          MigrationRouting migrationRouting) {
        LazyConnectionDataSourceProxy dataSource = new LazyConnectionDataSourceProxy(new MigrationRoutingDataSource(
            migrationDataSources.v1(), migrationDataSources.v2(), migrationRouting::determineWriteDatabase));
        dataSource.setReadOnlyDataSource(new MigrationRoutingDataSource(
            migrationDataSources.v1(), migrationDataSources.v2(), migrationRouting::determineReadDatabase));
        return dataSource;
    }

    @Bean(defaultCandidate = false)
    AsyncTaskExecutor shadowReadExecutor() {
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("shadow-read-");
        executor.setConcurrencyLimit(SHADOW_READ_CONCURRENCY);
        executor.setRejectTasksWhenLimitReached(true);
        return executor;
    }

    @Bean(defaultCandidate = false)
    TaskScheduler shadowReadScheduler() {
        SimpleAsyncTaskScheduler scheduler = new SimpleAsyncTaskScheduler();
        scheduler.setThreadNamePrefix("shadow-read-recompare-");
        return scheduler;
    }

    public record MigrationDataSources(HikariDataSource v1, HikariDataSource v2) implements AutoCloseable {

        @Override
        public void close() {
            v1.close();
            v2.close();
        }
    }
}
