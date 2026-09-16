package com.homes.zipsai.global.security;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.filter.OncePerRequestFilter;

import com.homes.zipsai.global.exception.ApiException;
import com.homes.zipsai.global.exception.ForbiddenException;
import com.homes.zipsai.global.exception.TooManyRequestsException;
import com.homes.zipsai.global.response.ApiResponse;

import tools.jackson.databind.ObjectMapper;

public class RequestGuard extends OncePerRequestFilter {
    private final AuthProperties properties;
    private final ObjectMapper json;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    private record Window(long start, int count) {
    }

    public RequestGuard(AuthProperties properties, ObjectMapper json) {
        this.properties = properties;
        this.json = json;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain
    ) throws ServletException, IOException {
        String path = request.getServletPath();
        if (!path.startsWith("/api/v1/")) {
            chain.doFilter(request, response);
            return;
        }

        response.setHeader("Cache-Control", "no-store");
        if (!request.getMethod().equals("GET") && !request.getMethod().equals("OPTIONS")) {
            String origin = request.getHeader("Origin");
            if ("cross-site".equals(request.getHeader("Sec-Fetch-Site"))
                    || (origin != null && !properties.allowedOrigins().contains(origin))) {
                write(response, new ForbiddenException());
                return;
            }
        }

        boolean limited = path.startsWith("/api/v1/auth/")
                || path.equals("/api/v1/users/email-availability")
                || path.equals("/api/v1/users/me");
        if (limited && !request.getMethod().equals("OPTIONS")) {
            long now = System.currentTimeMillis();
            if (windows.size() > 10000) {
                windows.entrySet().removeIf(entry -> now - entry.getValue().start() >= 30000);
            }
            String key = request.getRemoteAddr() + ":" + request.getMethod() + ":" + path;
            if (windows.size() >= 20000 && !windows.containsKey(key)) {
                reject(response, 30);
                return;
            }
            Window window = windows.compute(key, (windowKey, previous) -> {
                if (previous == null || now - previous.start() >= 30000) {
                    return new Window(now, 1);
                }
                return new Window(
                        previous.start(),
                        Math.min(previous.count() + 1, properties.rateLimit() + 1)
                );
            });
            if (window.count() > properties.rateLimit()) {
                reject(response, (int) Math.max(1, (30000 - (now - window.start()) + 999) / 1000));
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private void reject(HttpServletResponse response, int seconds) throws IOException {
        response.setHeader("Retry-After", Integer.toString(seconds));
        write(response, new TooManyRequestsException());
    }

    private void write(HttpServletResponse response, ApiException exception) throws IOException {
        response.setStatus(exception.status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(json.writeValueAsString(ApiResponse.error(exception)));
    }
}
