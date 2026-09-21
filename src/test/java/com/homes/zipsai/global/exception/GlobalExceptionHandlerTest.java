package com.homes.zipsai.global.exception;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ExceptionController())
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void usesTheCommonFailureShapeForApiExceptions() throws Exception {
        mockMvc.perform(get("/forbidden"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("해당 리소스에 접근할 권한이 없습니다."))
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.error.details.reason").value("요청한 리소스에 대한 접근 권한이 없습니다."))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    void createsTypeMismatchReasonFromTheException() {
        ApiException exception = InvalidQueryParameterException.typeMismatch("page", Integer.class);

        assertEquals(400, exception.status);
        assertEquals("INVALID_QUERY_PARAMETER", exception.code);
        assertEquals(
                Map.of("violations", List.of(Map.of("field", "page", "reason", "정수 형식이어야 합니다."))),
                exception.details);
    }

    @Test
    void usesFieldSpecificQueryReasonsFromApiSheet() {
        assertEquals(
                Map.of(
                        "violations",
                        List.of(Map.of("field", "status", "reason", "허용되지 않은 상태값입니다."))),
                InvalidQueryParameterException.typeMismatch("status", QueryStatus.class).details);
        assertEquals(
                Map.of(
                        "violations",
                        List.of(Map.of("field", "sort", "reason", "허용되지 않은 정렬 조건입니다."))),
                InvalidQueryParameterException.typeMismatch("sort", String.class).details);
    }

    @Test
    void createsValidationReasonFromTheException() {
        ApiException exception = new ValidationFailedException(
                "passwordConfirm",
                ValidationFailedException.Reason.PASSWORD_CONFIRMATION_MISMATCH);

        assertEquals(422, exception.status);
        assertEquals("VALIDATION_FAILED", exception.code);
        assertEquals(
                Map.of(
                        "violations",
                        List.of(Map.of("field", "passwordConfirm", "reason", "비밀번호가 일치하지 않습니다."))),
                exception.details);
    }

    @Test
    void doesNotExposeCallerDefinedMissingFieldResponses() {
        assertThrows(
                NoSuchMethodException.class,
                () -> MissingFieldException.class.getConstructor(String.class, String.class));
        assertThrows(
                NoSuchMethodException.class,
                () -> MissingFieldException.class.getConstructor(List.class));
    }

    @Test
    void mapsEveryApiExceptionToItsHttpStatusAndCode() throws Exception {
        List<ExceptionCase> cases = List.of(
                new ExceptionCase("missing-required", 400, "MISSING_REQUIRED_FIELD"),
                new ExceptionCase("invalid-query", 400, "INVALID_QUERY_PARAMETER"),
                new ExceptionCase("unauthorized", 401, "UNAUTHORIZED"),
                new ExceptionCase("forbidden", 403, "FORBIDDEN"),
                new ExceptionCase("email-already-exists", 409, "EMAIL_ALREADY_EXISTS"),
                new ExceptionCase("validation-failed", 422, "VALIDATION_FAILED"),
                new ExceptionCase("too-many-requests", 429, "TOO_MANY_REQUESTS"),
                new ExceptionCase("internal-server", 500, "INTERNAL_SERVER_ERROR")
        );

        for (ExceptionCase exceptionCase : cases) {
            mockMvc.perform(get("/api-exceptions/{type}", exceptionCase.type()))
                    .andExpect(status().is(exceptionCase.status()))
                    .andExpect(jsonPath("$.error.code").value(exceptionCase.code()))
                    .andExpect(jsonPath("$.data").value(nullValue()));
        }
    }

    @Test
    void putsFieldValidationErrorsUnderDetailsViolations() throws Exception {
        mockMvc.perform(post("/validation").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("입력값이 유효하지 않습니다."))
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details.violations[0].field").value("email"))
                .andExpect(jsonPath("$.error.details.violations[0].reason").value("이메일 형식이 올바르지 않습니다."))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    void mapsMalformedJsonToBadRequest() throws Exception {
        mockMvc.perform(post("/json").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("요청 형식이 올바르지 않습니다."))
                .andExpect(jsonPath("$.error.code").value("MISSING_REQUIRED_FIELD"))
                .andExpect(jsonPath("$.error.details.violations[0].field").value("body"))
                .andExpect(jsonPath("$.error.details.violations[0].reason")
                        .value("요청 본문 형식이 올바르지 않습니다."))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    void mapsMissingRequestBodyToBadRequest() throws Exception {
        mockMvc.perform(post("/json").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("MISSING_REQUIRED_FIELD"))
                .andExpect(jsonPath("$.error.details.violations[0].field").value("body"))
                .andExpect(jsonPath("$.error.details.violations[0].reason").value("필수 입력값입니다."))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    void mapsMissingRequiredValidationFieldToBadRequest() throws Exception {
        mockMvc.perform(post("/missing-field"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("MISSING_REQUIRED_FIELD"))
                .andExpect(jsonPath("$.error.details.violations[0].field").value("content"))
                .andExpect(jsonPath("$.error.details.violations[0].reason").value("필수 입력값입니다."))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    void includesRetryAfterSecondsForRateLimits() throws Exception {
        mockMvc.perform(get("/rate-limit"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("TOO_MANY_REQUESTS"))
                .andExpect(jsonPath("$.error.details.retryAfterSeconds").value(30))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    void mapsMissingRequestParameterToBadRequest() throws Exception {
        mockMvc.perform(get("/required"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("요청 형식이 올바르지 않습니다."))
                .andExpect(jsonPath("$.error.code").value("MISSING_REQUIRED_FIELD"))
                .andExpect(jsonPath("$.error.details.violations[0].field").value("keyword"))
                .andExpect(jsonPath("$.error.details.violations[0].reason").value("필수 입력값입니다."))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    void mapsRequestParameterTypeMismatchToBadRequest() throws Exception {
        mockMvc.perform(get("/typed").param("page", "not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("요청 형식이 올바르지 않습니다."))
                .andExpect(jsonPath("$.error.code").value("INVALID_QUERY_PARAMETER"))
                .andExpect(jsonPath("$.error.details.violations[0].field").value("page"))
                .andExpect(jsonPath("$.error.details.violations[0].reason").value("정수 형식이어야 합니다."))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    void mapsPathVariableTypeMismatchToValidationFailed() throws Exception {
        mockMvc.perform(get("/path/not-an-id"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details.violations[0].field").value("roomId"))
                .andExpect(jsonPath("$.error.details.violations[0].reason").value("1 이상의 정수여야 합니다."));
    }

    @Test
    void mapsQueryValidationToInvalidQueryParameter() throws Exception {
        mockMvc.perform(get("/query-validation"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_QUERY_PARAMETER"))
                .andExpect(jsonPath("$.error.details.violations[0].field").value("size"))
                .andExpect(jsonPath("$.error.details.violations[0].reason").value("정수 형식이어야 합니다."))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    void hidesUnexpectedExceptionDetails() throws Exception {
        mockMvc.perform(get("/internal-error"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("서버 오류가 발생했습니다."))
                .andExpect(jsonPath("$.error.code").value("INTERNAL_SERVER_ERROR"))
                .andExpect(jsonPath("$.error.details").isEmpty())
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(content().string(not(containsString("secret internal detail"))));
    }

    @RestController
    static class ExceptionController {

        private static final MethodParameter VALIDATION_PARAMETER = validationParameter();

        @GetMapping("/forbidden")
        void forbidden() {
            throw new ForbiddenException();
        }

        @GetMapping("/api-exceptions/{type}")
        void apiException(@PathVariable String type) {
            throw switch (type) {
                case "missing-required" -> new MissingFieldException("email");
                case "invalid-query" -> InvalidQueryParameterException.typeMismatch("page", Integer.class);
                case "unauthorized" -> new UnauthorizedException();
                case "forbidden" -> new ForbiddenException();
                case "email-already-exists" ->
                        new ConflictException(ConflictException.Reason.EMAIL_ALREADY_EXISTS);
                case "validation-failed" -> new ValidationFailedException(
                        "email", ValidationFailedException.Reason.INVALID_EMAIL_FORMAT);
                case "too-many-requests" -> new TooManyRequestsException();
                case "internal-server" -> new InternalServerException();
                default -> new IllegalArgumentException(type);
            };
        }

        @PostMapping("/validation")
        void validation() throws MethodArgumentNotValidException {
            BeanPropertyBindingResult bindingResult =
                    new BeanPropertyBindingResult(new Object(), "request");
            bindingResult.addError(new FieldError("request", "email", "이메일 형식이 올바르지 않습니다."));
            throw new MethodArgumentNotValidException(VALIDATION_PARAMETER, bindingResult);
        }

        @PostMapping("/json")
        void json(@RequestBody ValidationRequest request) {
        }

        @GetMapping("/required")
        void required(@RequestParam String keyword) {
        }

        @PostMapping("/missing-field")
        void missingField() throws MethodArgumentNotValidException {
            BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "request");
            bindingResult.addError(new FieldError(
                    "request",
                    "content",
                    null,
                    false,
                    new String[]{"NotNull.request.content", "NotNull.content", "NotNull"},
                    null,
                    "content는 필수입니다."
            ));
            throw new MethodArgumentNotValidException(VALIDATION_PARAMETER, bindingResult);
        }

        @GetMapping("/typed")
        void typed(@RequestParam int page) {
        }

        @GetMapping("/path/{roomId}")
        void pathId(@PathVariable long roomId) {
        }

        @GetMapping("/query-validation")
        void queryValidation() throws BindException {
            BindException bindingResult = new BindException(new Object(), "query");
            bindingResult.addError(new FieldError(
                    "query",
                    "size",
                    null,
                    false,
                    new String[]{"typeMismatch.query.size", "typeMismatch.size", "typeMismatch"},
                    null,
                    "정수 형식이어야 합니다."
            ));
            throw bindingResult;
        }

        @GetMapping("/internal-error")
        void internalError() {
            throw new IllegalStateException("secret internal detail");
        }

        @GetMapping("/rate-limit")
        void rateLimit() {
            throw new TooManyRequestsException();
        }

        private void validationArgument(ValidationRequest request) {
        }

        private static MethodParameter validationParameter() {
            try {
                Method method = ExceptionController.class.getDeclaredMethod(
                        "validationArgument", ValidationRequest.class);
                return new MethodParameter(method, 0);
            } catch (NoSuchMethodException exception) {
                throw new AssertionError(exception);
            }
        }
    }

    record ValidationRequest(String email) {
    }

    record ExceptionCase(String type, int status, String code) {
    }

    enum QueryStatus {
        UNKNOWN
    }
}
