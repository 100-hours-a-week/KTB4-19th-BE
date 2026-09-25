package com.homes.zipsai.user.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import com.homes.zipsai.global.response.ApiResponse;
import com.homes.zipsai.global.security.AuthPrincipal;
import com.homes.zipsai.user.dto.request.UserPatchRequest;

class UserControllerDtoContractTest {

    @Test
    void userEndpointsExposeTypedResponseDtos() throws NoSuchMethodException {
        assertThat(responseDataType("available", String.class))
                .isEqualTo("com.homes.zipsai.user.dto.response.EmailAvailabilityResponse");
        assertThat(responseDataType("me", AuthPrincipal.class))
                .isEqualTo("com.homes.zipsai.user.dto.response.UserProfileResponse");
        assertThat(responseDataType("patch", AuthPrincipal.class, UserPatchRequest.class))
                .isEqualTo("com.homes.zipsai.user.dto.response.UserPatchResponse");
    }

    private String responseDataType(String methodName, Class<?>... parameterTypes) throws NoSuchMethodException {
        Method method = UserController.class.getMethod(methodName, parameterTypes);
        Type returnType = method.getGenericReturnType();
        if (returnType instanceof ParameterizedType responseEntity
                && responseEntity.getRawType().equals(ResponseEntity.class)) {
            returnType = responseEntity.getActualTypeArguments()[0];
        }

        ParameterizedType apiResponse = (ParameterizedType) returnType;
        if (!apiResponse.getRawType().equals(ApiResponse.class)) {
            throw new AssertionError("UserController endpoints must return ApiResponse<T>");
        }
        return apiResponse.getActualTypeArguments()[0].getTypeName();
    }
}
