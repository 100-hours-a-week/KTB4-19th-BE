package com.homes.zipsai.global.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class TaskLoggingDecoratorTest {

    private static final String TRACE_ID = "trace-123";
    private static final String ROUTE = "/api/v1/residents/me/conversations";
    private static final String STAGE = "chatbot_reply";

    StructuredLogger structuredLogger = mock(StructuredLogger.class);
    TaskLoggingDecorator taskLoggingDecorator = new TaskLoggingDecorator(structuredLogger, STAGE);

    @BeforeEach
    void setUpCallerMdc() {
        MDC.put("traceId", TRACE_ID);
        MDC.put("route", ROUTE);
    }

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("작업을 맡긴 요청의 traceId를 작업에 넘겨준다")
    void copiesCallerMdcToTask() {
        AtomicReference<String> taskTraceId = new AtomicReference<>();
        Runnable task = taskLoggingDecorator.decorate(() -> taskTraceId.set(MDC.get("traceId")));
        MDC.clear();

        task.run();

        assertThat(taskTraceId).hasValue(TRACE_ID);
    }

    @Test
    @DisplayName("작업이 끝나면 요청의 traceId로 작업 중 DB 시간을 mysql 단계로 남긴다")
    void logsDatabaseTimeOfTaskWithCallerTraceId() {
        Runnable task = taskLoggingDecorator.decorate(() -> JdbcTimingContext.record(5_000_000L, false));

        task.run();

        then(structuredLogger).should().stageDone(TRACE_ID, ROUTE, "mysql", 5L, "ok", null);
    }

    @Test
    @DisplayName("작업이 끝나면 요청의 traceId로 작업 전체 시간을 성공으로 남긴다")
    void logsTaskDurationAsSuccess() {
        Runnable task = taskLoggingDecorator.decorate(() -> { });

        task.run();

        then(structuredLogger).should().stageDone(eq(TRACE_ID), eq(ROUTE), eq(STAGE), anyLong(), eq("ok"), isNull());
    }

    @Test
    @DisplayName("작업이 오류 코드를 기록하면 작업 단계를 실패와 그 오류 코드로 남긴다")
    void logsTaskAsFailureWithRecordedErrorCode() {
        Runnable task = taskLoggingDecorator.decorate(() -> MDC.put(StructuredLogger.ERROR_CODE, "AI_UNAVAILABLE"));

        task.run();

        then(structuredLogger).should()
            .stageDone(eq(TRACE_ID), eq(ROUTE), eq(STAGE), anyLong(), eq("fail"), eq("AI_UNAVAILABLE"));
    }

    @Test
    @DisplayName("작업이 끝나면 MDC와 DB 측정값을 비운다")
    void clearsMdcAndDatabaseTimingAfterTask() {
        Runnable task = taskLoggingDecorator.decorate(() -> MDC.put(StructuredLogger.ERROR_CODE, "AI_UNAVAILABLE"));

        task.run();

        JdbcTimingContext.record(5_000_000L, false);
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
        assertThat(JdbcTimingContext.finish().durationMillis()).isZero();
    }
}
