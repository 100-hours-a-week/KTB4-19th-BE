package com.homes.zipsai.user.controller;

import com.homes.zipsai.user.repository.UserRepository;
import com.homes.zipsai.user.validator.UserInput;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.homes.zipsai.global.response.ApiResponse;
import com.homes.zipsai.global.security.AuthPrincipal;
import com.homes.zipsai.user.dto.UserPatchRequest;
import com.homes.zipsai.user.dto.UserPatchResponse;
import com.homes.zipsai.user.service.UserService;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserRepository users;
    private final UserService service;

    public UserController(UserRepository users, UserService service) {
        this.users = users;
        this.service = service;
    }

    @GetMapping("/email-availability")
    public ApiResponse<Map<String, Object>> available(
            @RequestParam(required = false) String email
    ) {
        String normalized = UserInput.email(email);
        return ApiResponse.data(Map.of(
                "email", normalized,
                "isAvailable", !users.existsByEmail(normalized)
        ));
    }

    @GetMapping("/me")
    public ApiResponse<Map<String, Object>> me(
            @AuthenticationPrincipal AuthPrincipal principal
    ) {
        return ApiResponse.data(service.me(principal.userId()));
    }

    // V3_P2: NONE에서 최초 한 번만 역할을 선택한다.
    @PatchMapping("/me")
    public ApiResponse<UserPatchResponse> patch(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestBody UserPatchRequest request
    ) {
        return ApiResponse.data(service.patch(principal, request));
    }
}
