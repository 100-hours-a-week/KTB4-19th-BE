package com.homes.zipsai.global.response;


import com.fasterxml.jackson.annotation.JsonInclude;
import com.homes.zipsai.global.exception.ApiException;

public record ApiResponse<T>(
    @JsonInclude(JsonInclude.Include.NON_NULL)
    String message,
    T data,
    ErrorResponse error) {
    public record ErrorResponse(String code, Object details) {}
    public static <T> ApiResponse<T> data(T value) { return new ApiResponse<>(null, value, null); }
    public static <T> ApiResponse<T> success(T value) { return new ApiResponse<>("success", value, null); }
    public static ApiResponse<Void> error(ApiException e) { return new ApiResponse<>(e.getMessage(), null, new ErrorResponse(e.code, e.details)); }
}
