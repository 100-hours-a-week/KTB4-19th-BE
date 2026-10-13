package com.homes.zipsai.migration.aspect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import java.time.Instant;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.support.TaskExecutorAdapter;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.transaction.annotation.Transactional;

import com.homes.zipsai.migration.domain.MigrationDatabase;
import com.homes.zipsai.migration.domain.MigrationMode;
import com.homes.zipsai.migration.domain.MigrationSettings;
import com.homes.zipsai.migration.domain.WorkerMode;
import com.homes.zipsai.migration.service.MigrationRouting;
import com.homes.zipsai.migration.service.MigrationSettingsProvider;
import com.homes.zipsai.migration.service.ShadowReadComparator;

import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class ShadowReadAspectTest {

    private static final String METHOD = "UserService.findUserName(..)";

    @Mock
    MigrationRouting migrationRouting;

    @Mock
    TaskScheduler shadowReadScheduler;

    @Mock
    ProceedingJoinPoint joinPoint;

    @Mock
    Signature signature;

    ShadowReadAspect shadowReadAspect;

    @BeforeEach
    void setUp() {
        MigrationSettingsProvider migrationSettingsProvider = new MigrationSettingsProvider();
        migrationSettingsProvider.update(
            new MigrationSettings(MigrationMode.V1_PRIMARY, null, WorkerMode.PAUSED, false, false, 0, true));
        shadowReadAspect = new ShadowReadAspect(migrationRouting, migrationSettingsProvider,
            new ShadowReadComparator(JsonMapper.builder().build()), new TaskExecutorAdapter(Runnable::run),
            shadowReadScheduler);
        given(joinPoint.getSignature()).willReturn(signature);
        given(signature.toShortString()).willReturn(METHOD);
        given(migrationRouting.chooseReadDatabase()).willReturn(MigrationDatabase.V1);
    }

    @Test
    @DisplayName("V1과 V2 결과가 다르면 5초 뒤 재비교를 예약한다")
    void schedulesRecompareWhenResultsDiffer() throws Throwable {
        givenReadResults("세탁기", "건조기");

        shadowReadAspect.routeRead(joinPoint, readOnly());

        then(shadowReadScheduler).should().schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    @DisplayName("V1과 V2 결과가 같으면 재비교하지 않는다")
    void skipsRecompareWhenResultsAreSame() throws Throwable {
        givenReadResults("세탁기", "세탁기");

        shadowReadAspect.routeRead(joinPoint, readOnly());

        then(shadowReadScheduler).should(never()).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    @DisplayName("재비교할 때는 V1과 V2를 둘 다 다시 읽는다")
    void rereadsBothDatabasesWhenRecomparing() throws Throwable {
        givenReadResults("세탁기", "건조기", "세탁기");
        givenRecompareRunsImmediately();

        shadowReadAspect.routeRead(joinPoint, readOnly());

        then(migrationRouting).should(times(2)).useReadDatabase(eq(MigrationDatabase.V1), any());
        then(migrationRouting).should(times(2)).useReadDatabase(eq(MigrationDatabase.V2), any());
    }

    @Test
    @DisplayName("비교 결과와 관계없이 V1 결과로 응답한다")
    void returnsV1Result() throws Throwable {
        givenReadResults("세탁기", "건조기");

        Object result = shadowReadAspect.routeRead(joinPoint, readOnly());

        assertThat(result).isEqualTo("세탁기");
    }

    private void givenReadResults(Object v1Result, Object v2Result) throws Throwable {
        given(migrationRouting.useReadDatabase(eq(MigrationDatabase.V1), any())).willReturn(v1Result);
        given(migrationRouting.useReadDatabase(eq(MigrationDatabase.V2), any())).willReturn(v2Result);
    }

    private void givenReadResults(Object v1Result, Object v2Result, Object recomparedV2Result) throws Throwable {
        given(migrationRouting.useReadDatabase(eq(MigrationDatabase.V1), any())).willReturn(v1Result);
        given(migrationRouting.useReadDatabase(eq(MigrationDatabase.V2), any()))
            .willReturn(v2Result, recomparedV2Result);
    }

    private void givenRecompareRunsImmediately() {
        given(shadowReadScheduler.schedule(any(Runnable.class), any(Instant.class))).willAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return null;
        });
    }

    private Transactional readOnly() throws NoSuchMethodException {
        return ReadOnlyQuery.class.getDeclaredMethod("find").getAnnotation(Transactional.class);
    }

    static class ReadOnlyQuery {

        @Transactional(readOnly = true)
        void find() {
        }
    }
}
