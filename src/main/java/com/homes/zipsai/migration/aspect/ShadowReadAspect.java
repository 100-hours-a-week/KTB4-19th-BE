package com.homes.zipsai.migration.aspect;

import java.time.Duration;
import java.time.Instant;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.homes.zipsai.migration.config.ConditionalOnMigrationEnabled;
import com.homes.zipsai.migration.domain.MigrationDatabase;
import com.homes.zipsai.migration.domain.ShadowReadResult;
import com.homes.zipsai.migration.service.MigrationEventLogger;
import com.homes.zipsai.migration.service.MigrationMetrics;
import com.homes.zipsai.migration.service.MigrationRouting;
import com.homes.zipsai.migration.service.MigrationSettingsProvider;
import com.homes.zipsai.migration.service.ShadowReadComparator;

@Aspect
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 1)
@ConditionalOnMigrationEnabled
public class ShadowReadAspect {

    private static final Logger LOGGER = LoggerFactory.getLogger(ShadowReadAspect.class);
    private static final Duration RECOMPARE_DELAY = Duration.ofSeconds(5);

    private final MigrationRouting migrationRouting;
    private final MigrationSettingsProvider migrationSettingsProvider;
    private final ShadowReadComparator shadowReadComparator;
    private final AsyncTaskExecutor shadowReadExecutor;
    private final TaskScheduler shadowReadScheduler;
    private final MigrationMetrics migrationMetrics;
    private final MigrationEventLogger migrationEventLogger;

    public ShadowReadAspect(MigrationRouting migrationRouting, MigrationSettingsProvider migrationSettingsProvider,
                            ShadowReadComparator shadowReadComparator,
                            @Qualifier("shadowReadExecutor") AsyncTaskExecutor shadowReadExecutor,
                            @Qualifier("shadowReadScheduler") TaskScheduler shadowReadScheduler,
                            MigrationMetrics migrationMetrics, MigrationEventLogger migrationEventLogger) {
        this.migrationRouting = migrationRouting;
        this.migrationSettingsProvider = migrationSettingsProvider;
        this.shadowReadComparator = shadowReadComparator;
        this.shadowReadExecutor = shadowReadExecutor;
        this.shadowReadScheduler = shadowReadScheduler;
        this.migrationMetrics = migrationMetrics;
        this.migrationEventLogger = migrationEventLogger;
    }

    @Around("execution(* com.homes.zipsai..service..*(..)) && @annotation(transactional)")
    public Object routeRead(ProceedingJoinPoint joinPoint, Transactional transactional) throws Throwable {
        if (!transactional.readOnly() || TransactionSynchronizationManager.isActualTransactionActive()) {
            return joinPoint.proceed();
        }
        MigrationDatabase database = migrationRouting.chooseReadDatabase();
        Object result = migrationRouting.useReadDatabase(database, joinPoint::proceed);
        if (database == MigrationDatabase.V1 && migrationSettingsProvider.getSettings().shadowReadEnabled()) {
            compareV2Result(joinPoint, result);
        }
        return result;
    }

    private void compareV2Result(ProceedingJoinPoint joinPoint, Object v1Result) {
        String method = joinPoint.getSignature().toShortString();
        try {
            shadowReadExecutor.execute(() -> {
                try {
                    Object v2Result = migrationRouting.useReadDatabase(MigrationDatabase.V2, joinPoint::proceed);
                    if (shadowReadComparator.isSame(v1Result, v2Result)) {
                        migrationMetrics.recordShadowRead(ShadowReadResult.MATCH);
                        return;
                    }
                    shadowReadScheduler.schedule(() -> recompare(joinPoint, method),
                        Instant.now().plus(RECOMPARE_DELAY));
                } catch (Throwable throwable) {
                    migrationMetrics.recordShadowRead(ShadowReadResult.ERROR);
                    LOGGER.warn("Shadow Read V2 조회에 실패했습니다. method={}", method, throwable);
                }
            });
        } catch (TaskRejectedException exception) {
            LOGGER.debug("Shadow Read 대기열이 가득 차 비교를 건너뜁니다. method={}", method);
        }
    }

    private void recompare(ProceedingJoinPoint joinPoint, String method) {
        try {
            shadowReadExecutor.execute(() -> {
                try {
                    Object v1Result = migrationRouting.useReadDatabase(MigrationDatabase.V1, joinPoint::proceed);
                    Object v2Result = migrationRouting.useReadDatabase(MigrationDatabase.V2, joinPoint::proceed);
                    if (shadowReadComparator.isSame(v1Result, v2Result)) {
                        migrationMetrics.recordShadowRead(ShadowReadResult.MISMATCH_TRANSIENT);
                        return;
                    }
                    migrationMetrics.recordShadowRead(ShadowReadResult.MISMATCH_PERSISTENT);
                    migrationEventLogger.shadowReadMismatch(method, ShadowReadResult.MISMATCH_PERSISTENT.getTag());
                    LOGGER.warn("Shadow Read 결과가 다시 비교해도 V1과 다릅니다. method={}", method);
                } catch (Throwable throwable) {
                    migrationMetrics.recordShadowRead(ShadowReadResult.ERROR);
                    LOGGER.warn("Shadow Read 재비교 조회에 실패했습니다. method={}", method, throwable);
                }
            });
        } catch (TaskRejectedException exception) {
            LOGGER.debug("Shadow Read 대기열이 가득 차 재비교를 건너뜁니다. method={}", method);
        }
    }
}
