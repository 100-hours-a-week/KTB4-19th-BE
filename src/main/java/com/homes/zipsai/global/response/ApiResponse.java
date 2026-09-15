package com.homes.zipsai.global.response;

import java.util.LinkedHashMap;
import java.util.Map;

import com.homes.zipsai.global.exception.ApiException;

public final class ApiResponse {
    private ApiResponse() {}
    public static Map<String, Object> data(Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("data", value); return result;
    }
    public static Map<String, Object> error(ApiException e) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("message", e.getMessage());
        result.put("error", Map.of("code", e.code, "details", e.details));
        result.put("data", null); return result;
    }
}
