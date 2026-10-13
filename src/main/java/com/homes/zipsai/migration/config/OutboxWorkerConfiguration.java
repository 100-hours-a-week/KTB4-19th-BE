package com.homes.zipsai.migration.config;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.SimpleAsyncTaskScheduler;
import org.springframework.transaction.PlatformTransactionManager;

import com.zaxxer.hikari.HikariDataSource;

@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty("app.migration.worker-enabled")
public class OutboxWorkerConfiguration {

    private static final int POOL_SIZE = 2;
    private static final String DISABLE_FOREIGN_KEY_CHECKS = "SET SESSION foreign_key_checks = 0";

    @Bean(defaultCandidate = false)
    DataSource outboxWorkerV1DataSource(DataSourceProperties dataSourceProperties) {
        return workerDataSource("outbox-worker-v1", dataSourceProperties.determineUrl(),
            dataSourceProperties.determineUsername(), dataSourceProperties.determinePassword());
    }

    @Bean(defaultCandidate = false)
    DataSource outboxWorkerV2DataSource(MigrationProperties migrationProperties) {
        MigrationProperties.V2Datasource v2Datasource = migrationProperties.v2Datasource();
        return workerDataSource("outbox-worker-v2", v2Datasource.url(), v2Datasource.username(),
            v2Datasource.password());
    }

    @Bean(defaultCandidate = false)
    PlatformTransactionManager outboxWorkerV1TransactionManager(
            @Qualifier("outboxWorkerV1DataSource") DataSource outboxWorkerV1DataSource) {
        return new DataSourceTransactionManager(outboxWorkerV1DataSource);
    }

    @Bean(defaultCandidate = false)
    PlatformTransactionManager outboxWorkerV2TransactionManager(
            @Qualifier("outboxWorkerV2DataSource") DataSource outboxWorkerV2DataSource) {
        return new DataSourceTransactionManager(outboxWorkerV2DataSource);
    }

    @Bean(defaultCandidate = false)
    TaskScheduler migrationTaskScheduler() {
        SimpleAsyncTaskScheduler taskScheduler = new SimpleAsyncTaskScheduler();
        taskScheduler.setThreadNamePrefix("outbox-worker-");
        return taskScheduler;
    }

    private static HikariDataSource workerDataSource(String poolName, String url, String username, String password) {
        HikariDataSource dataSource = DataSourceBuilder.create()
            .type(HikariDataSource.class)
            .url(url)
            .username(username)
            .password(password)
            .build();
        dataSource.setPoolName(poolName);
        dataSource.setMaximumPoolSize(POOL_SIZE);
        dataSource.setConnectionInitSql(DISABLE_FOREIGN_KEY_CHECKS);
        return dataSource;
    }
}
