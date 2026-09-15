package com.homes.zipsai_backend.user;

import com.homes.zipsai_backend.auth.AuthPrincipal;
import com.homes.zipsai_backend.common.ApiResponse;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController @RequestMapping("/api/v1/users")
public class UserController {
    private final UserRepository users;
    private final UserService service;
    public UserController(UserRepository users, UserService service) { this.users = users; this.service = service; }
    @GetMapping("/email-availability")
    public Map<String, Object> available(@RequestParam(required = false) String email) {
        String normalized = UserInput.email(email);
        return ApiResponse.data(Map.of("email", normalized, "isAvailable", !users.existsByEmail(normalized)));
    }
    @GetMapping("/me")
    public Map<String, Object> me(@AuthenticationPrincipal AuthPrincipal principal) { return ApiResponse.data(service.me(principal.userId())); }
    // V3_P2: NONE에서 최초 한 번만 역할을 선택한다.
    @PatchMapping("/me")
    public Map<String, Object> patch(@AuthenticationPrincipal AuthPrincipal principal, @RequestBody JsonNode body) {
        return ApiResponse.data(service.patch(principal, body));
    }
}
