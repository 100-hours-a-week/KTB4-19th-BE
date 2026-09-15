package com.homes.zipsai.security;

import com.homes.zipsai.auth.AuthProperties;
import com.homes.zipsai.common.ApiException;
import com.homes.zipsai.common.ApiResponse;
import com.homes.zipsai.common.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

public class RequestGuard extends OncePerRequestFilter {
    private final AuthProperties properties;
    private final ObjectMapper json;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private record Window(long start, int count) {}
    public RequestGuard(AuthProperties properties, ObjectMapper json) { this.properties = properties; this.json = json; }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        String path = request.getServletPath();
        if (!path.startsWith("/api/v1/")) { chain.doFilter(request, response); return; }
        response.setHeader("Cache-Control", "no-store");
        if (!request.getMethod().equals("GET") && !request.getMethod().equals("OPTIONS")) {
            String origin = request.getHeader("Origin");
            if ("cross-site".equals(request.getHeader("Sec-Fetch-Site")) || (origin != null && !properties.allowedOrigins().contains(origin))) {
                write(response, ApiException.forbidden()); return;
            }
        }
        boolean limited = path.startsWith("/api/v1/auth/") || path.equals("/api/v1/users/email-availability") || path.equals("/api/v1/users/me");
        if (limited && !request.getMethod().equals("OPTIONS")) {
            long now = System.currentTimeMillis();
            if (windows.size() > 10000) windows.entrySet().removeIf(e -> now - e.getValue().start() >= 30000);
            String key = request.getRemoteAddr() + ":" + request.getMethod() + ":" + path;
            if (windows.size() >= 20000 && !windows.containsKey(key)) { reject(response, 30); return; }
            Window window = windows.compute(key, (k, old) -> old == null || now - old.start() >= 30000
                ? new Window(now, 1) : new Window(old.start(), Math.min(old.count()+1, properties.rateLimit()+1)));
            if (window.count() > properties.rateLimit()) { reject(response, (int)Math.max(1, (30000-(now-window.start())+999)/1000)); return; }
        }
        chain.doFilter(request, response);
    }
    private void reject(HttpServletResponse response, int seconds) throws IOException {
        response.setHeader("Retry-After", Integer.toString(seconds));
        write(response, new ApiException(429, "TOO_MANY_REQUESTS", "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.", Map.of("retryAfterSeconds", seconds)));
    }
    private void write(HttpServletResponse response, ApiException e) throws IOException {
        response.setStatus(e.status); response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(json.writeValueAsString(ApiResponse.error(e)));
    }
}
