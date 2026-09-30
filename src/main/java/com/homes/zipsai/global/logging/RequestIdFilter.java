package com.homes.zipsai.global.logging;

import java.io.IOException;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import lombok.RequiredArgsConstructor;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class RequestIdFilter extends OncePerRequestFilter {
    private static final String TRACE_ID = "traceId";
    private final StructuredLogger structuredLogger;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String traceId = UUID.randomUUID().toString();
        long started = System.nanoTime();
        JdbcTimingContext.begin();
        try {
            MDC.put(TRACE_ID, traceId);
            MDC.put("route", request.getRequestURI());
            response.setHeader("X-Request-Id", traceId);
            MDC.remove("dbMs");
            MDC.remove("aiApiMs");
            MDC.remove(StructuredLogger.STATUS_CODE);
            MDC.remove(StructuredLogger.ERROR_CODE);
            safelyLog(() -> structuredLogger.requestStarted(traceId, request.getRequestURI(), request.getMethod()));
            filterChain.doFilter(request, response);
        } finally {
            JdbcTimingContext.Measurement database = JdbcTimingContext.finish();
            long databaseMs = database.durationMillis();
            long totalMs = (System.nanoTime() - started) / 1_000_000;
            try {
                MDC.put("dbMs", Long.toString(databaseMs));
                safelyLog(() -> structuredLogger.stageDone(traceId, request.getRequestURI(), "mysql", databaseMs,
                    database.outcome(), null));
                safelyLog(() -> structuredLogger.requestDone(traceId, request.getRequestURI(), request.getMethod(),
                    response.getStatus(), totalMs, null));
            } finally {
                JdbcTimingContext.clear();
                MDC.remove(TRACE_ID);
                MDC.remove("route");
                MDC.remove("dbMs");
                MDC.remove("aiApiMs");
                MDC.remove(StructuredLogger.STATUS_CODE);
                MDC.remove(StructuredLogger.ERROR_CODE);
            }
        }
    }

    private void safelyLog(Runnable loggingCall) {
        try {
            loggingCall.run();
        } catch (RuntimeException ignored) {

        }
    }
}
