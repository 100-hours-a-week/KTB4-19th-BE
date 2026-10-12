package com.homes.zipsai.global.logging;

import java.util.Map;

import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public final class TaskLoggingDecorator implements TaskDecorator {
    private final StructuredLogger structuredLogger;
    private final String stage;

    @Override
    public Runnable decorate(Runnable task) {
        Map<String, String> callerMdc = MDC.getCopyOfContextMap();
        return () -> {
            if (callerMdc != null) {
                MDC.setContextMap(callerMdc);
            }
            long started = System.nanoTime();
            JdbcTimingContext.begin(structuredLogger, MDC.get("traceId"), MDC.get("route"));
            try {
                task.run();
            } finally {
                logTaskDone(started);
                MDC.clear();
            }
        };
    }

    private void logTaskDone(long started) {
        String traceId = MDC.get("traceId");
        String route = MDC.get("route");
        String errorCode = MDC.get(StructuredLogger.ERROR_CODE);
        JdbcTimingContext.Measurement database = JdbcTimingContext.finish();
        long totalMs = (System.nanoTime() - started) / 1_000_000;
        structuredLogger.stageDone(traceId, route, "mysql", database.durationMillis(), database.outcome(), null);
        structuredLogger.stageDone(traceId, route, stage, totalMs, errorCode == null ? "ok" : "fail", errorCode);
    }
}
