package com.homes.zipsai;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class AuthApiTests {
    @Autowired MockMvc mvc;

    @Autowired tools.jackson.databind.ObjectMapper json;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    private String email() { return java.util.UUID.randomUUID() + "@example.com"; }
    private String signupBody(String email) {
        return """
            {"email":"%s","password":"Asdf!12345","passwordConfirm":"Asdf!12345",
             "agreements":[{"termsType":"SERVICE","isAgreed":true},{"termsType":"PRIVACY","isAgreed":true},{"termsType":"MARKETING","isAgreed":false}]}
            """.formatted(email);
    }
    private void signup(String email) throws Exception {
        mvc.perform(post("/api/v1/auth/signup").contentType("application/json").content(signupBody(email))).andExpect(status().isCreated());
    }
    private org.springframework.test.web.servlet.MvcResult login(String email) throws Exception {
        return mvc.perform(post("/api/v1/auth/login").contentType("application/json").content(
            """
            {"email":"%s","password":"Asdf!12345"}
            """.formatted(email))).andExpect(status().isOk()).andReturn();
    }
    private String access(org.springframework.test.web.servlet.MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString()).path("data").path("accessToken").asText();
    }
    private jakarta.servlet.http.Cookie refresh(org.springframework.test.web.servlet.MvcResult result) {
        return result.getResponse().getCookie("refreshToken");
    }
    private org.springframework.test.web.servlet.ResultActions patchMe(String token, String body) throws Exception {
        return mvc.perform(patch("/api/v1/users/me").header("Authorization", "Bearer " + token).contentType("application/json").content(body));
    }

    @Test void duplicateEmailIsCanonicalAndDoesNotDuplicateAgreements() throws Exception {
        String email = email(); signup(email);
        mvc.perform(post("/api/v1/auth/signup").contentType("application/json").content(signupBody("  " + email.toUpperCase() + "  ")))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("EMAIL_ALREADY_EXISTS"));
        mvc.perform(get("/api/v1/users/email-availability").param("email", email))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.isAvailable").value(false));
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("select count(*) from User_agreements a join Users u on a.user_id=u.user_id where u.email=?", Integer.class, email)).isEqualTo(3);
    }
    @Test void rejectsMissingRequiredAgreementAndPrivilegeInjection() throws Exception {
        mvc.perform(post("/api/v1/auth/signup").contentType("application/json").content(signupBody(email()).replace("\"SERVICE\",\"isAgreed\":true", "\"SERVICE\",\"isAgreed\":false")))
            .andExpect(status().isUnprocessableEntity());
        mvc.perform(post("/api/v1/auth/signup").contentType("application/json").content(signupBody(email()).replace("{\"email\"", "{\"userRole\":\"MANAGER\",\"email\"")))
            .andExpect(status().isUnprocessableEntity());
    }
    @Test void validatesTypesPasswordAndPhone() throws Exception {
        for (String body : java.util.List.of(
            signupBody(email()).replace("Asdf!12345", "short"),
            signupBody(email()).replace("{\"email\"", "{\"phone\":\"010xyz12345678\",\"email\""),
            signupBody(email()).replace("\"passwordConfirm\":\"Asdf!12345\"", "\"passwordConfirm\":\"Different!12\""),
            signupBody(email()).replace("{\"email\"", "{\"phone\":123,\"email\""))) {
            mvc.perform(post("/api/v1/auth/signup").contentType("application/json").content(body)).andExpect(status().isUnprocessableEntity());
        }
        mvc.perform(post("/api/v1/auth/login").contentType("application/json").content("{}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("MISSING_REQUIRED_FIELD"));
        mvc.perform(post("/api/v1/auth/login").contentType("application/json").content("{"))
            .andExpect(status().isBadRequest());
    }
    @Test void storesHashedPasswordAndReturnsOnlyOwnProfile() throws Exception {
        String email = email(); signup(email); var login = login(email);
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("select password from Users where email=?", String.class, email))
            .startsWith("$2a$").isNotEqualTo("Asdf!12345");
        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + access(login)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.email").value(email))
            .andExpect(jsonPath("$.data.userRole").value("NONE")).andExpect(jsonPath("$.data.phone").isEmpty())
            .andExpect(jsonPath("$.data.password").doesNotExist()).andExpect(jsonPath("$.data.refreshToken").doesNotExist());
        org.assertj.core.api.Assertions.assertThat(login.getResponse().getHeader("Set-Cookie")).contains("HttpOnly", "SameSite=Strict", "Path=/api/v1/auth");
    }
    @Test void loginFailureDoesNotRevealAccountExistence() throws Exception {
        String email = email(); signup(email);
        for (String target : java.util.List.of(email, email())) {
            mvc.perform(post("/api/v1/auth/login").contentType("application/json").content("""
                {"email":"%s","password":"Wrong!12345"}
                """.formatted(target)))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.details.reason").value("이메일 또는 비밀번호가 일치하지 않습니다."));
        }
    }
    @Test void refreshRotatesAndLogoutRevokesAccessAndRefresh() throws Exception {
        String email = email(); signup(email); var login = login(email);
        var rotated = mvc.perform(post("/api/v1/auth/reissue").cookie(refresh(login))).andExpect(status().isOk()).andReturn();
        mvc.perform(post("/api/v1/auth/reissue").cookie(refresh(login))).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer " + access(rotated)))
            .andExpect(status().isOk()).andExpect(cookie().maxAge("refreshToken", 0));
        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + access(rotated))).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/reissue").cookie(refresh(rotated))).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/reissue")).andExpect(status().isUnauthorized());
    }
    @Test void roleSelectionIsOneTimeAndInvalidatesOldAccess() throws Exception {
        String email = email(); signup(email); var login = login(email); String old = access(login);
        mvc.perform(get("/api/v1/managers/probe").header("Authorization", "Bearer " + old)).andExpect(status().isForbidden());
        var selected = patchMe(old, "{\"userRole\":\"MANAGER\"}").andExpect(status().isOk()).andReturn();
        String token = access(selected);
        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + old)).andExpect(status().isUnauthorized());
        patchMe(token, "{\"userRole\":\"MANAGER\"}").andExpect(status().isOk());
        patchMe(token, "{\"userRole\":\"RESIDENT\"}").andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ROLE_ALREADY_ASSIGNED"));
        mvc.perform(get("/api/v1/residents/probe").header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/managers/probe").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
    }
    @Test void profilePatchPreservesOmittedFieldsAndAllowsClearingPhone() throws Exception {
        String email = email(); signup(email); String token = access(login(email));
        patchMe(token, "{\"userName\":\"김관리\",\"phone\":\"01012345678\"}")
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.phone").value("010-1234-5678"));
        patchMe(token, "{\"phone\":null}").andExpect(status().isOk()).andExpect(jsonPath("$.data.userName").doesNotExist());
        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + token))
            .andExpect(jsonPath("$.data.userName").value("김관리")).andExpect(jsonPath("$.data.phone").isEmpty());
        patchMe(token, "{\"userStatus\":\"INACTIVE\"}").andExpect(status().isUnprocessableEntity());
    }
    @Test void invalidProfilePatchRollsBackRoleChange() throws Exception {
        String email = email(); signup(email); String token = access(login(email));
        patchMe(token, "{\"userRole\":\"MANAGER\",\"phone\":\"bad\"}").andExpect(status().isUnprocessableEntity());
        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.userRole").value("NONE"));
    }
    @Test void rejectsUnauthenticatedForgedAndCrossOriginRequests() throws Exception {
        mvc.perform(get("/api/v1/users/me")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer invalid.token.value")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/reissue").header("Origin", "https://evil.example" )).andExpect(status().isForbidden());
    }
    @Test void inactiveAccountCannotUseExistingTokens() throws Exception {
        String email = email(); signup(email); var login = login(email);
        jdbc.update("update Users set user_status='INACTIVE' where email=?", email);
        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + access(login))).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/reissue").cookie(refresh(login))).andExpect(status().isUnauthorized());
    }
    @Test void concurrentSignupCreatesExactlyOneAccount() throws Exception {
        String email = email();
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var gate = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Callable<Integer> call = () -> { gate.await(); return mvc.perform(post("/api/v1/auth/signup").contentType("application/json").content(signupBody(email))).andReturn().getResponse().getStatus(); };
            var a = pool.submit(call); var b = pool.submit(call); gate.countDown();
            org.assertj.core.api.Assertions.assertThat(java.util.List.of(a.get(), b.get())).containsExactlyInAnyOrder(201, 409);
        }
    }
    @Test void concurrentRefreshAllowsOnlyOneRotation() throws Exception {
        String email = email(); signup(email); var login = login(email);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var gate = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Callable<Integer> call = () -> { gate.await(); return mvc.perform(post("/api/v1/auth/reissue").cookie(refresh(login))).andReturn().getResponse().getStatus(); };
            var a = pool.submit(call); var b = pool.submit(call); gate.countDown();
            org.assertj.core.api.Assertions.assertThat(java.util.List.of(a.get(), b.get())).containsExactlyInAnyOrder(200, 401);
        }
    }
    @Test void methodSecurityDenialUses403Envelope() throws Exception {
        String email = email(); signup(email); String token = access(login(email));
        mvc.perform(get("/api/v1/probe/manager-only").header("Authorization", "Bearer " + token))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }
    @Test void expiredSessionCannotRefreshOrAuthenticate() throws Exception {
        String email = email(); signup(email); var login = login(email);
        jdbc.update("update Refresh_sessions set expires_at=? where user_id=(select user_id from Users where email=?)", java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(60)), email);
        mvc.perform(post("/api/v1/auth/reissue").cookie(refresh(login))).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + access(login))).andExpect(status().isUnauthorized());
    }
    @Test void marketingAgreementChangeKeepsHistory() throws Exception {
        String email = email(); signup(email); String token = access(login(email));
        patchMe(token, "{\"agreements\":[{\"termsType\":\"MARKETING\",\"isAgreed\":true}]}")
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.agreements[0].isAgreed").value(true));
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("select count(*) from User_agreements a join Users u on a.user_id=u.user_id join terms t on a.terms_id=t.terms_id where u.email=? and t.terms_type='MARKETING'", Integer.class, email)).isEqualTo(2);
    }
    @org.springframework.boot.test.context.TestConfiguration
    static class ProbeConfig {
        @org.springframework.context.annotation.Bean ProbeController probeController() { return new ProbeController(); }
    }
    @org.springframework.web.bind.annotation.RestController
    static class ProbeController {
        @org.springframework.web.bind.annotation.GetMapping("/api/v1/probe/manager-only")
        @org.springframework.security.access.prepost.PreAuthorize("hasRole('MANAGER')")
        public java.util.Map<String,String> methodGuard() { return java.util.Map.of("role", "MANAGER"); }
        @org.springframework.web.bind.annotation.GetMapping("/api/v1/managers/probe")
        java.util.Map<String,String> manager() { return java.util.Map.of("role", "MANAGER"); }
    }

    @Test void signupAllowsMissingPhoneAndDoesNotIssueTokens() throws Exception {
        mvc.perform(post("/api/v1/auth/signup").contentType("application/json").content("""
            {"email":"nullable@example.com","password":"Asdf!12345","passwordConfirm":"Asdf!12345",
             "agreements":[{"termsType":"SERVICE","isAgreed":true},{"termsType":"PRIVACY","isAgreed":true},{"termsType":"MARKETING","isAgreed":false}]}
            """))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.data.userId").isNumber())
            .andExpect(jsonPath("$.data.accessToken").doesNotExist())
            .andExpect(header().doesNotExist("Set-Cookie"));
    }
}
