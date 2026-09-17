package com.homes.zipsai.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import jakarta.servlet.http.Cookie;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.homes.zipsai.ZipsaiBackendApplication;

import tools.jackson.databind.ObjectMapper;

@SpringBootTest(classes = ZipsaiBackendApplication.class)
@AutoConfigureMockMvc
@Import(AuthApiTests.ProbeConfig.class)
class AuthApiTests {
    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    JdbcTemplate jdbc;

    private String email() {
        return UUID.randomUUID() + "@example.com";
    }

    private String signupBody(String email) {
        return """
                {"email":"%s","password":"Asdf!12345","passwordConfirm":"Asdf!12345",
                 "agreements":[
                     {"termsType":"SERVICE","isAgreed":true},
                     {"termsType":"PRIVACY","isAgreed":true},
                     {"termsType":"MARKETING","isAgreed":false}
                 ]}
                """.formatted(email);
    }

    private void signup(String email) throws Exception {
        mvc.perform(
                post("/api/v1/auth/signup")
                        .contentType("application/json")
                        .content(signupBody(email))
        ).andExpect(status().isCreated());
    }

    private MvcResult login(String email) throws Exception {
        return mvc.perform(
                post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content("""
                                {"email":"%s","password":"Asdf!12345"}
                                """.formatted(email))
        ).andExpect(status().isOk()).andReturn();
    }

    private String access(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString())
                .path("data")
                .path("accessToken")
                .asText();
    }

    private Cookie refresh(MvcResult result) {
        return result.getResponse().getCookie("refreshToken");
    }

    private ResultActions patchMe(String token, String body) throws Exception {
        return mvc.perform(
                patch("/api/v1/users/me")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(body)
        );
    }

    @Test
    void duplicateEmailIsCanonicalAndDoesNotDuplicateAgreements() throws Exception {
        String email = email();
        signup(email);

        mvc.perform(
                post("/api/v1/auth/signup")
                        .contentType("application/json")
                        .content(signupBody("  " + email.toUpperCase() + "  "))
        ).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("EMAIL_ALREADY_EXISTS"));

        mvc.perform(get("/api/v1/users/email-availability").param("email", email))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isAvailable").value(false));

        String query = "select count(*) from User_agreements a "
                + "join Users u on a.user_id=u.user_id where u.email=?";
        Assertions.assertThat(jdbc.queryForObject(query, Integer.class, email)).isEqualTo(3);
    }

    @Test
    void rejectsMissingRequiredAgreement() throws Exception {
        mvc.perform(
                post("/api/v1/auth/signup")
                        .contentType("application/json")
                        .content(signupBody(email())
                                .replace("\"SERVICE\",\"isAgreed\":true", "\"SERVICE\",\"isAgreed\":false"))
        ).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        mvc.perform(
                post("/api/v1/auth/signup")
                        .contentType("application/json")
                        .content("""
                                {"email":"%s","password":"Asdf!12345","passwordConfirm":"Asdf!12345"}
                                """.formatted(email()))
        ).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("MISSING_REQUIRED_FIELD"));

        mvc.perform(
                post("/api/v1/auth/signup")
                        .contentType("application/json")
                        .content(signupBody(email()))
        ).andExpect(status().isCreated());
    }

    @Test
    void validatesTypesPasswordAndPhone() throws Exception {
        for (String body : List.of(
                signupBody(email()).replace("Asdf!12345", "short"),
                signupBody(email()).replace("{\"email\"", "{\"phone\":\"010xyz12345678\",\"email\""),
                signupBody(email()).replace(
                        "\"passwordConfirm\":\"Asdf!12345\"",
                        "\"passwordConfirm\":\"Different!12\""
                ),
                signupBody(email()).replace("{\"email\"", "{\"phone\":123,\"email\"")
        )) {
            mvc.perform(
                    post("/api/v1/auth/signup")
                            .contentType("application/json")
                            .content(body)
            ).andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        }

        mvc.perform(
                post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content("{}")
        ).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("MISSING_REQUIRED_FIELD"));

        mvc.perform(
                post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content("{")
        ).andExpect(status().isBadRequest());
    }

    @Test
    void storesHashedPasswordAndReturnsOnlyOwnProfile() throws Exception {
        String email = email();
        signup(email);
        MvcResult loginResult = login(email);

        Assertions.assertThat(
                jdbc.queryForObject("select password from Users where email=?", String.class, email)
        ).startsWith("$2a$").isNotEqualTo("Asdf!12345");

        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + access(loginResult)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value(email))
                .andExpect(jsonPath("$.data.userRole").value("NONE"))
                .andExpect(jsonPath("$.data.phone").isEmpty())
                .andExpect(jsonPath("$.data.password").doesNotExist())
                .andExpect(jsonPath("$.data.refreshToken").doesNotExist());

        Assertions.assertThat(loginResult.getResponse().getHeader("Set-Cookie"))
                .contains("HttpOnly", "SameSite=Strict", "Path=/api/v1/auth");
    }

    @Test
    void loginFailureDoesNotRevealAccountExistence() throws Exception {
        String email = email();
        signup(email);

        for (String target : List.of(email, email())) {
            mvc.perform(
                    post("/api/v1/auth/login")
                            .contentType("application/json")
                            .content("""
                                    {"email":"%s","password":"Wrong!12345"}
                                    """.formatted(target))
            ).andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error.details.reason")
                            .value("이메일 또는 비밀번호가 일치하지 않습니다."));
        }
    }

    @Test
    void refreshRotatesAndLogoutRevokesAccessAndRefresh() throws Exception {
        String email = email();
        signup(email);
        MvcResult loginResult = login(email);
        MvcResult rotated = mvc.perform(
                post("/api/v1/auth/reissue").cookie(refresh(loginResult))
        ).andExpect(status().isOk()).andReturn();

        mvc.perform(post("/api/v1/auth/reissue").cookie(refresh(loginResult)))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer " + access(rotated)))
                .andExpect(status().isOk())
                .andExpect(cookie().maxAge("refreshToken", 0));
        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + access(rotated)))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/reissue").cookie(refresh(rotated)))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/reissue"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void roleSelectionIsOneTimeAndInvalidatesOldAccess() throws Exception {
        String email = email();
        signup(email);
        MvcResult loginResult = login(email);
        String oldToken = access(loginResult);

        mvc.perform(get("/api/v1/managers/probe").header("Authorization", "Bearer " + oldToken))
                .andExpect(status().isForbidden());
        MvcResult selected = patchMe(oldToken, "{\"userRole\":\"MANAGER\"}")
                .andExpect(status().isOk())
                .andReturn();
        String token = access(selected);

        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + oldToken))
                .andExpect(status().isUnauthorized());
        patchMe(token, "{\"userRole\":\"MANAGER\"}").andExpect(status().isOk());
        patchMe(token, "{\"userRole\":\"RESIDENT\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ROLE_ALREADY_ASSIGNED"));
        mvc.perform(get("/api/v1/residents/probe").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/managers/probe").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void profilePatchPreservesOmittedFieldsAndAllowsClearingPhone() throws Exception {
        String email = email();
        signup(email);
        String token = access(login(email));

        patchMe(token, "{\"userName\":\"김관리\",\"phone\":\"01012345678\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.phone").value("010-1234-5678"));
        patchMe(token, "{\"phone\":\"\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userName").doesNotExist());
        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.userName").value("김관리"))
                .andExpect(jsonPath("$.data.phone").isEmpty());
    }

    @Test
    void invalidProfilePatchRollsBackRoleChange() throws Exception {
        String email = email();
        signup(email);
        String token = access(login(email));

        patchMe(token, "{\"userRole\":\"MANAGER\",\"phone\":\"bad\"}")
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userRole").value("NONE"));
    }

    @Test
    void rejectsUnauthenticatedForgedAndCrossOriginRequests() throws Exception {
        mvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
        mvc.perform(get("/api/v1/users/me")
                .header("Authorization", "Bearer invalid.token.value"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/reissue").header("Origin", "https://evil.example"))
                .andExpect(status().isForbidden());
    }

    @Test
    void inactiveAccountCannotUseExistingTokens() throws Exception {
        String email = email();
        signup(email);
        MvcResult loginResult = login(email);

        jdbc.update("update Users set user_status='INACTIVE' where email=?", email);
        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + access(loginResult)))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/reissue").cookie(refresh(loginResult)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void concurrentSignupCreatesExactlyOneAccount() throws Exception {
        String email = email();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            Callable<Integer> call = () -> {
                gate.await();
                return mvc.perform(
                        post("/api/v1/auth/signup")
                                .contentType("application/json")
                                .content(signupBody(email))
                ).andReturn().getResponse().getStatus();
            };
            var first = pool.submit(call);
            var second = pool.submit(call);
            gate.countDown();

            Assertions.assertThat(List.of(first.get(), second.get()))
                    .containsExactlyInAnyOrder(201, 409);
        }
    }

    @Test
    void concurrentRefreshAllowsOnlyOneRotation() throws Exception {
        String email = email();
        signup(email);
        MvcResult loginResult = login(email);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            Callable<Integer> call = () -> {
                gate.await();
                return mvc.perform(
                        post("/api/v1/auth/reissue").cookie(refresh(loginResult))
                ).andReturn().getResponse().getStatus();
            };
            var first = pool.submit(call);
            var second = pool.submit(call);
            gate.countDown();

            Assertions.assertThat(List.of(first.get(), second.get()))
                    .containsExactlyInAnyOrder(200, 401);
        }
    }

    @Test
    void methodSecurityDenialUses403Envelope() throws Exception {
        String email = email();
        signup(email);
        String token = access(login(email));

        mvc.perform(get("/api/v1/probe/manager-only").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void expiredSessionCannotRefreshOrAuthenticate() throws Exception {
        String email = email();
        signup(email);
        MvcResult loginResult = login(email);

        jdbc.update(
                "update Refresh_sessions set expires_at=? "
                        + "where user_id=(select user_id from Users where email=?)",
                Timestamp.from(Instant.now().minusSeconds(60)),
                email
        );
        mvc.perform(post("/api/v1/auth/reissue").cookie(refresh(loginResult)))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + access(loginResult)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void marketingAgreementChangeKeepsHistory() throws Exception {
        String email = email();
        signup(email);
        String token = access(login(email));

        patchMe(token, "{\"agreements\":[{\"termsType\":\"MARKETING\",\"isAgreed\":true}]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.agreements[0].isAgreed").value(true));
        String query = "select count(*) from User_agreements a "
                + "join Users u on a.user_id=u.user_id "
                + "join terms t on a.terms_id=t.terms_id "
                + "where u.email=? and t.terms_type='MARKETING'";
        Assertions.assertThat(jdbc.queryForObject(query, Integer.class, email)).isEqualTo(2);
    }

    @TestConfiguration
    static class ProbeConfig {
        @Bean
        ProbeController probeController() {
            return new ProbeController();
        }
    }

    @RestController
    static class ProbeController {
        @GetMapping("/api/v1/probe/manager-only")
        @PreAuthorize("hasRole('MANAGER')")
        public Map<String, String> methodGuard() {
            return Map.of("role", "MANAGER");
        }

        @GetMapping("/api/v1/managers/probe")
        Map<String, String> manager() {
            return Map.of("role", "MANAGER");
        }
    }

    @Test
    void signupAllowsMissingPhoneAndDoesNotIssueTokens() throws Exception {
        mvc.perform(
                post("/api/v1/auth/signup")
                        .contentType("application/json")
                        .content("""
                                {"email":"nullable@example.com","password":"Asdf!12345","passwordConfirm":"Asdf!12345",
                                 "agreements":[
                                     {"termsType":"SERVICE","isAgreed":true},
                                     {"termsType":"PRIVACY","isAgreed":true},
                                     {"termsType":"MARKETING","isAgreed":false}
                                 ]}
                                """)
        ).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.userId").isNumber())
                .andExpect(jsonPath("$.data.accessToken").doesNotExist())
                .andExpect(header().doesNotExist("Set-Cookie"));
    }
}
