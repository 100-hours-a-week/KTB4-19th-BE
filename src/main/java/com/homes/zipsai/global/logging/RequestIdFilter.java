package com.homes.zipsai.global.logging;

import java.io.IOException;
import java.util.UUID;
import java.util.Map;
import java.util.LinkedHashMap;
import java.time.Instant;

import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import lombok.RequiredArgsConstructor;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
@RequiredArgsConstructor
public class RequestIdFilter extends OncePerRequestFilter {
    private static final String TRACE_ID = "traceId";
    private static final String HEADER = "X-Trace-Id";
    private final StructuredLogger structuredLogger;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String traceId = request.getHeader(HEADER);
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString();
        }
        long started = System.nanoTime();
        MDC.put(TRACE_ID, traceId);
        response.setHeader(HEADER, traceId);
        structuredLogger.requestStarted(traceId, request.getRequestURI(), request.getMethod());
        try {
            filterChain.doFilter(request, response);
        } finally {
            structuredLogger.requestDone(traceId, request.getRequestURI(), request.getMethod(),
                response.getStatus(), (System.nanoTime() - started) / 1_000_000, null);
            MDC.remove(TRACE_ID);
        }
    }
}
