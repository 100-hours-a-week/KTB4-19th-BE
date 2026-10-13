package com.homes.zipsai.migration.service;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.homes.zipsai.migration.config.MigrationProperties;
import com.homes.zipsai.migration.domain.OutboxOperation;
import com.homes.zipsai.user.domain.User;

@ExtendWith(MockitoExtension.class)
class OutboxRecorderTest {

    @Mock
    JdbcClient jdbcClient;

    @Mock
    MigrationRouting migrationRouting;

    @Mock
    OutboxEntityTables outboxEntityTables;

    @Test
    @DisplayName("V2 접속 정보가 없으면 이벤트를 남기지 않는다")
    void skipsRecordingWithoutV2Datasource() {
        OutboxRecorder outboxRecorder = new OutboxRecorder(jdbcClient,
            new MigrationProperties(null, new MigrationProperties.V2Datasource("", "", "")), migrationRouting,
            outboxEntityTables);

        outboxRecorder.record(User.class, 1L, OutboxOperation.INSERT);

        then(jdbcClient).should(never()).sql(anyString());
    }
}
