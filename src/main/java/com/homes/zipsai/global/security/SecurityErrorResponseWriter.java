package com.homes.zipsai.global.security;

import java.io.IOException;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.stereotype.Component;

import com.homes.zipsai.global.exception.ApiException;
import com.homes.zipsai.global.response.ApiResponse;

import tools.jackson.databind.ObjectMapper;

@Component
public class SecurityErrorResponseWriter {
    private final ObjectMapper json;

    public SecurityErrorResponseWriter(ObjectMapper json) {
        this.json = json;
    }

    public void write(HttpServletResponse response, ApiException exception) throws IOException {
        response.setStatus(exception.status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(json.writeValueAsString(ApiResponse.error(exception)));
    }
}
