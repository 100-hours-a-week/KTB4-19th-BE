package com.homes.zipsai.migration.aspect;

import java.time.Duration;
import java.time.Instant;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
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
import com.homes.zipsai.migration.domain.ShadowReadComparison;
import com.homes.zipsai.migration.domain.ShadowReadPhase;
import com.homes.zipsai.migration.domain.ShadowReadQuery;
import com.homes.zipsai.migration.domain.ShadowReadRequest;
import com.homes.zipsai.migration.domain.ShadowReadResult;
import com.homes.zipsai.migration.service.MigrationEventLogger;
import com.homes.zipsai.migration.service.MigrationMetrics;
import com.homes.zipsai.migration.service.MigrationRouting;
import com.homes.zipsai.migration.service.ShadowReadComparator;

@Aspect
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 1)
@ConditionalOnMigrationEnabled
public class ShadowReadAspect {

    private static final Logger LOGGER = LoggerFactory.getLogger(ShadowReadAspect.class);
    private static final Duration RECOMPARE_DELAY = Duration.ofSeconds(30);
    private static final String TRACE_ID = "traceId";

    private final MigrationRouting migrationRouting;
    private final ShadowReadComparator shadowReadComparator;
    private final AsyncTaskExecutor shadowReadExecutor;
    private final TaskScheduler shadowReadScheduler;
    private final MigrationMetrics migrationMetrics;
    private final MigrationEventLogger migrationEventLogger;

    public ShadowReadAspect(MigrationRouting migrationRouting, ShadowReadComparator shadowReadComparator,
                            @Qualifier("shadowReadExecutor") AsyncTaskExecutor shadowReadExecutor,
                            @Qualifier("shadowReadScheduler") TaskScheduler shadowReadScheduler,
                            MigrationMetrics migrationMetrics, MigrationEventLogger migrationEventLogger) {
        this.migrationRouting = migrationRouting;
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
        String traceId = MDC.get(TRACE_ID);
        String method = joinPoint.getSignature().toShortString();
        ShadowReadQuery responseQuery = read(joinPoint, migrationRouting.chooseReadDatabase());
        migrationEventLogger.readServed(traceId, method, responseQuery);
        if (migrationRouting.shouldShadowRead()) {
            compareWithOtherDatabase(joinPoint, new ShadowReadRequest(traceId, method, responseQuery.database()),
                responseQuery);
        }
        return responseQuery.result();
    }

    private ShadowReadQuery read(ProceedingJoinPoint joinPoint, MigrationDatabase database) throws Throwable {
        Instant startedAt = Instant.now();
        Object result = migrationRouting.useReadDatabase(database, joinPoint::proceed);
        return new ShadowReadQuery(database, startedAt, Instant.now(), result);
    }

    private void compareWithOtherDatabase(ProceedingJoinPoint joinPoint, ShadowReadRequest request,
                                          ShadowReadQuery responseQuery) {
        runShadowRead(request, ShadowReadPhase.INITIAL, () -> {
            ShadowReadQuery otherQuery = read(joinPoint, request.responseDatabase().other());
            ShadowReadComparison comparison = shadowReadComparator.compare(responseQuery, otherQuery);
            if (comparison.isSame()) {
                migrationMetrics.recordShadowRead(ShadowReadResult.MATCH);
                return;
            }
            recordMismatch(request, ShadowReadPhase.INITIAL, ShadowReadResult.MISMATCH_INITIAL, comparison);
            shadowReadScheduler.schedule(() -> recompare(joinPoint, request), Instant.now().plus(RECOMPARE_DELAY));
        });
    }

    private void recompare(ProceedingJoinPoint joinPoint, ShadowReadRequest request) {
        runShadowRead(request, ShadowReadPhase.RECOMPARE, () -> {
            ShadowReadQuery responseQuery = read(joinPoint, request.responseDatabase());
            ShadowReadQuery otherQuery = read(joinPoint, request.responseDatabase().other());
            ShadowReadComparison comparison = shadowReadComparator.compare(responseQuery, otherQuery);
            ShadowReadResult result = comparison.isSame()
                ? ShadowReadResult.MISMATCH_RESOLVED
                : ShadowReadResult.MISMATCH_PERSISTENT;
            recordMismatch(request, ShadowReadPhase.RECOMPARE, result, comparison);
        });
    }

    private void recordMismatch(ShadowReadRequest request, ShadowReadPhase phase, ShadowReadResult result,
                                ShadowReadComparison comparison) {
        migrationMetrics.recordShadowRead(result);
        migrationEventLogger.shadowReadMismatch(request, phase, result, comparison);
    }

    private void runShadowRead(ShadowReadRequest request, ShadowReadPhase phase, ShadowReadTask task) {
        try {
            shadowReadExecutor.execute(() -> {
                try {
                    task.run();
                } catch (Throwable throwable) {
                    migrationMetrics.recordShadowRead(ShadowReadResult.ERROR);
                    migrationEventLogger.shadowReadError(request, phase, throwable);
                    LOGGER.warn("Shadow Read 조회에 실패했습니다. method={}, phase={}", request.method(),
                        phase.getTag(), throwable);
                }
            });
        } catch (TaskRejectedException exception) {
            migrationMetrics.recordShadowRead(ShadowReadResult.SKIPPED);
            migrationEventLogger.shadowReadSkipped(request, phase);
        }
    }

    @FunctionalInterface
    private interface ShadowReadTask {

        void run() throws Throwable;
    }
}
