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
import com.homes.zipsai.migration.service.MigrationEventLogger;
import com.homes.zipsai.migration.service.MigrationMetrics;
import com.homes.zipsai.migration.service.MigrationRouting;
import com.homes.zipsai.migration.service.MigrationSettingsProvider;
import com.homes.zipsai.migration.service.ShadowReadComparator;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class ShadowReadAspectTest {

    private static final String METHOD = "UserService.findUserName(..)";

    @Mock
    MigrationRouting migrationRouting;

    @Mock
    TaskScheduler shadowReadScheduler;

    @Mock
    MigrationEventLogger migrationEventLogger;

    @Mock
    ProceedingJoinPoint joinPoint;

    @Mock
    Signature signature;

    MeterRegistry meterRegistry;

    ShadowReadAspect shadowReadAspect;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        shadowReadAspect = new ShadowReadAspect(migrationRouting,
            new ShadowReadComparator(JsonMapper.builder().build()), new TaskExecutorAdapter(Runnable::run),
            shadowReadScheduler, new MigrationMetrics(meterRegistry, new MigrationSettingsProvider()),
            migrationEventLogger);
    }

    @Test
    @DisplayName("비교 대상으로 뽑히지 않으면 다른 DB를 조회하지 않는다")
    void skipsComparisonWhenNotSampled() throws Throwable {
        given(migrationRouting.chooseReadDatabase()).willReturn(MigrationDatabase.V1);
        given(migrationRouting.useReadDatabase(eq(MigrationDatabase.V1), any())).willReturn("세탁기");
        given(migrationRouting.shouldShadowRead()).willReturn(false);

        shadowReadAspect.routeRead(joinPoint, readOnly());

        then(migrationRouting).should(never()).useReadDatabase(eq(MigrationDatabase.V2), any());
    }

    @Test
    @DisplayName("비교 결과와 관계없이 응답 DB의 결과로 응답한다")
    void returnsResultOfResponseDatabase() throws Throwable {
        givenComparedReads(MigrationDatabase.V1, "세탁기", "건조기");

        Object result = shadowReadAspect.routeRead(joinPoint, readOnly());

        assertThat(result).isEqualTo("세탁기");
    }

    @Test
    @DisplayName("V2에서 응답했으면 V1과 비교한다")
    void comparesWithV1WhenRespondingFromV2() throws Throwable {
        givenComparedReads(MigrationDatabase.V2, "세탁기", "세탁기");

        shadowReadAspect.routeRead(joinPoint, readOnly());

        then(migrationRouting).should().useReadDatabase(eq(MigrationDatabase.V1), any());
    }

    @Test
    @DisplayName("결과가 같으면 일치로 세고 재비교하지 않는다")
    void countsMatchWithoutRecompare() throws Throwable {
        givenComparedReads(MigrationDatabase.V1, "세탁기", "세탁기");

        shadowReadAspect.routeRead(joinPoint, readOnly());

        assertThat(shadowReads("match")).isEqualTo(1);
        then(shadowReadScheduler).should(never()).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    @DisplayName("결과가 다르면 최초 불일치로 세고 5초 뒤 재비교를 예약한다")
    void countsInitialMismatchAndSchedulesRecompare() throws Throwable {
        givenComparedReads(MigrationDatabase.V1, "세탁기", "건조기");

        shadowReadAspect.routeRead(joinPoint, readOnly());

        assertThat(shadowReads("mismatch_initial")).isEqualTo(1);
        then(shadowReadScheduler).should().schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    @DisplayName("재비교할 때는 두 DB를 모두 다시 읽는다")
    void rereadsBothDatabasesWhenRecomparing() throws Throwable {
        givenComparedReads(MigrationDatabase.V1, "세탁기", "건조기", "세탁기");
        givenRecompareRunsImmediately();

        shadowReadAspect.routeRead(joinPoint, readOnly());

        then(migrationRouting).should(times(2)).useReadDatabase(eq(MigrationDatabase.V1), any());
        then(migrationRouting).should(times(2)).useReadDatabase(eq(MigrationDatabase.V2), any());
    }

    @Test
    @DisplayName("재비교에서 같아졌으면 지속 불일치로 세지 않는다")
    void skipsPersistentMismatchWhenRecomparisonMatches() throws Throwable {
        givenComparedReads(MigrationDatabase.V1, "세탁기", "건조기", "세탁기");
        givenRecompareRunsImmediately();

        shadowReadAspect.routeRead(joinPoint, readOnly());

        assertThat(shadowReads("mismatch_persistent")).isZero();
    }

    @Test
    @DisplayName("재비교해도 다르면 지속 불일치로 세고 로그를 남긴다")
    void countsPersistentMismatchAndLogs() throws Throwable {
        givenComparedReads(MigrationDatabase.V1, "세탁기", "건조기", "건조기");
        givenRecompareRunsImmediately();

        shadowReadAspect.routeRead(joinPoint, readOnly());

        assertThat(shadowReads("mismatch_persistent")).isEqualTo(1);
        then(migrationEventLogger).should().shadowReadMismatch(METHOD, "mismatch_persistent");
    }

    @Test
    @DisplayName("비교 조회에 실패하면 오류로 센다")
    void countsErrorWhenComparisonReadFails() throws Throwable {
        givenShadowRead(MigrationDatabase.V1);
        given(migrationRouting.useReadDatabase(eq(MigrationDatabase.V1), any())).willReturn("세탁기");
        given(migrationRouting.useReadDatabase(eq(MigrationDatabase.V2), any()))
            .willThrow(new IllegalStateException("V2 down"));

        shadowReadAspect.routeRead(joinPoint, readOnly());

        assertThat(shadowReads("error")).isEqualTo(1);
    }

    private void givenShadowRead(MigrationDatabase responseDatabase) {
        given(joinPoint.getSignature()).willReturn(signature);
        given(signature.toShortString()).willReturn(METHOD);
        given(migrationRouting.chooseReadDatabase()).willReturn(responseDatabase);
        given(migrationRouting.shouldShadowRead()).willReturn(true);
    }

    private void givenComparedReads(MigrationDatabase responseDatabase, Object result, Object otherResult)
        throws Throwable {
        givenShadowRead(responseDatabase);
        given(migrationRouting.useReadDatabase(eq(responseDatabase), any())).willReturn(result);
        given(migrationRouting.useReadDatabase(eq(responseDatabase.other()), any())).willReturn(otherResult);
    }

    private void givenComparedReads(MigrationDatabase responseDatabase, Object result, Object otherResult,
                                    Object recomparedOtherResult) throws Throwable {
        givenShadowRead(responseDatabase);
        given(migrationRouting.useReadDatabase(eq(responseDatabase), any())).willReturn(result);
        given(migrationRouting.useReadDatabase(eq(responseDatabase.other()), any()))
            .willReturn(otherResult, recomparedOtherResult);
    }

    private void givenRecompareRunsImmediately() {
        given(shadowReadScheduler.schedule(any(Runnable.class), any(Instant.class))).willAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return null;
        });
    }

    private double shadowReads(String result) {
        Counter counter = meterRegistry.find("migration.shadow.read").tag("result", result).counter();
        return counter == null ? 0 : counter.count();
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
