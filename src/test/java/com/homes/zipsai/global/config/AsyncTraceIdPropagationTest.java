package com.homes.zipsai.global.config;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.timeout;

import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.homes.zipsai.global.logging.StructuredLogger;

@SpringBootTest(classes = {AsyncConfig.class, AsyncTraceIdPropagationTest.TraceRecorder.class})
@ImportAutoConfiguration(TaskExecutionAutoConfiguration.class)
class AsyncTraceIdPropagationTest {

    private static final String TRACE_ID = "trace-async-123";
    private static final String HANDED_OFF_TRACE_ID = "trace-handed-off-456";
    private static final String ASYNC_TASK_STAGE = "async_task";

    @MockitoBean
    StructuredLogger structuredLogger;

    @Autowired
    TraceRecorder traceRecorder;

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("일반 메서드는 요청 스레드에서 요청의 traceId로 실행된다")
    void runsPlainMethodOnCallerThreadWithCallerTraceId() {
        MDC.put("traceId", TRACE_ID);

        TaskTrace trace = traceRecorder.recordNow();

        assertThat(trace.thread()).isEqualTo(Thread.currentThread().getName());
        assertThat(trace.traceId()).isEqualTo(TRACE_ID);
    }

    @Test
    @DisplayName("@Async 메서드는 작업 스레드에서 요청의 traceId로 실행되고 async_task 단계 로그를 남긴다")
    void runsAsyncMethodOnWorkerThreadWithCallerTraceId() throws Exception {
        MDC.put("traceId", TRACE_ID);

        TaskTrace trace = traceRecorder.recordLater().get(5, SECONDS);

        assertThat(trace.thread()).isNotEqualTo(Thread.currentThread().getName()).startsWith("task-");
        assertThat(trace.traceId()).isEqualTo(TRACE_ID);
        then(structuredLogger).should(timeout(1_000))
            .stageDone(eq(TRACE_ID), isNull(), eq(ASYNC_TASK_STAGE), anyLong(), eq("ok"), isNull());
    }

    @Test
    @DisplayName("@Async 메서드에 작업을 넘긴 뒤에도 요청 스레드의 traceId는 남아 있다")
    void keepsCallerTraceIdAfterHandingOffAsyncMethod() throws Exception {
        MDC.put("traceId", HANDED_OFF_TRACE_ID);

        traceRecorder.recordLater().get(5, SECONDS);

        assertThat(MDC.get("traceId")).isEqualTo(HANDED_OFF_TRACE_ID);
    }

    @Test
    @DisplayName("요청의 traceId 없이 넘긴 @Async 메서드는 traceId 없이 실행되고 단계 로그를 남기지 않는다")
    void logsNoStageWhenAsyncMethodHasNoCallerTraceId() throws Exception {
        TaskTrace trace = traceRecorder.recordLater().get(5, SECONDS);

        assertThat(trace.traceId()).isNull();
        then(structuredLogger).should(after(300).never())
            .stageDone(isNull(), any(), any(), anyLong(), any(), any());
    }

    record TaskTrace(String thread, String traceId) {
    }

    static class TraceRecorder {

        private static final Logger LOGGER = LoggerFactory.getLogger(TraceRecorder.class);

        TaskTrace recordNow() {
            return record("일반 호출");
        }

        @Async
        CompletableFuture<TaskTrace> recordLater() {
            return CompletableFuture.completedFuture(record("@Async 호출"));
        }

        private TaskTrace record(String call) {
            TaskTrace trace = new TaskTrace(Thread.currentThread().getName(), MDC.get("traceId"));
            LOGGER.info("[{}] thread={}, traceId={}", call, trace.thread(), trace.traceId());
            return trace;
        }
    }
}
