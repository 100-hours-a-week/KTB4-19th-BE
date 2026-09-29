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
        MDC.put(TRACE_ID, traceId);
        MDC.put("route", request.getRequestURI());
        response.setHeader("X-Request-Id", traceId);
        MDC.remove("dbMs");
        MDC.remove("aiApiMs");
        MDC.remove(StructuredLogger.STATUS_CODE);
        MDC.remove(StructuredLogger.ERROR_CODE);
        structuredLogger.requestStarted(traceId, request.getRequestURI(), request.getMethod());
        try {
            filterChain.doFilter(request, response);
        } finally {
            structuredLogger.requestDone(traceId, request.getRequestURI(), request.getMethod(),
                response.getStatus(), (System.nanoTime() - started) / 1_000_000, null);
            MDC.remove(TRACE_ID);
            MDC.remove("route");
            MDC.remove("dbMs");
            MDC.remove("aiApiMs");
            MDC.remove(StructuredLogger.STATUS_CODE);
            MDC.remove(StructuredLogger.ERROR_CODE);
        }
    }
}
