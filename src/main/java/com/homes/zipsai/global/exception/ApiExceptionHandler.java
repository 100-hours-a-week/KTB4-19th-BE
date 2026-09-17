package com.homes.zipsai.global.exception;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.MethodParameter;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.BindException;
import org.springframework.validation.Errors;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingPathVariableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.homes.zipsai.global.response.ApiResponse;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<?> handle(ApiException exception) {
        return ResponseEntity.status(exception.status).body(ApiResponse.error(exception));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<?> requestBodyValidation(MethodArgumentNotValidException exception) {
        return bodyValidation(exception.getBindingResult());
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<?> methodValidation(HandlerMethodValidationException exception) {
        if (exception.isForReturnValue()) {
            return unexpected(exception);
        }
        List<Map<String, String>> queryViolations = new ArrayList<>();
        List<Map<String, String>> pathViolations = new ArrayList<>();
        for (ParameterValidationResult result : exception.getParameterValidationResults()) {
            if (result instanceof ParameterErrors errors) {
                return bodyValidation(errors);
            }
            MethodParameter parameter = result.getMethodParameter();
            for (var error : result.getResolvableErrors()) {
                Map<String, String> violation = Map.of(
                        "field", parameter.getParameterName(),
                        "reason", error.getDefaultMessage());
                if (parameter.hasParameterAnnotation(PathVariable.class)) {
                    pathViolations.add(violation);
                } else {
                    queryViolations.add(violation);
                }
            }
        }
        if (!queryViolations.isEmpty()) {
            return handle(new InvalidQueryParameterException(queryViolations));
        }
        return handle(new ValidationFailedException(pathViolations));
    }

    private ResponseEntity<?> bodyValidation(Errors bindingResult) {
        boolean missingRequiredField = bindingResult.getFieldErrors().stream()
                .anyMatch(ApiExceptionHandler::isMissingRequiredField);
        List<Map<String, String>> violations = violations(bindingResult);
        return missingRequiredField
                ? handle(new MissingFieldException(violations))
                : handle(new ValidationFailedException(violations));
    }

    @ExceptionHandler(BindException.class)
    public ResponseEntity<?> queryParameterValidation(BindException exception) {
        return handle(new InvalidQueryParameterException(violations(exception.getBindingResult())));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<?> malformed(HttpMessageNotReadableException exception) {
        boolean missingRequestBody = exception.getMessage() != null
                && exception.getMessage().startsWith("Required request body is missing");
        return missingRequestBody
                ? handle(new MissingFieldException("body"))
                : handle(new InvalidJsonException());
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<?> missingRequestParameter(MissingServletRequestParameterException exception) {
        return handle(new MissingFieldException(exception.getParameterName()));
    }

    @ExceptionHandler(MissingPathVariableException.class)
    public ResponseEntity<?> missingPathVariable(MissingPathVariableException exception) {
        return handle(new MissingFieldException(exception.getVariableName()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<?> typeMismatch(MethodArgumentTypeMismatchException exception) {
        if (exception.getParameter().hasParameterAnnotation(PathVariable.class)) {
            return handle(new ValidationFailedException(
                    exception.getName(), ValidationFailedException.Reason.INVALID_ID));
        }
        return handle(InvalidQueryParameterException.typeMismatch(
                exception.getName(), exception.getRequiredType()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<?> forbidden() {
        return handle(new ForbiddenException());
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<?> unauthorized() {
        return handle(new UnauthorizedException());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<?> notFound() {
        return handle(new NotFoundException());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> unexpected(Exception exception) {
        LOGGER.error("Unhandled API failure: {}", exception.getClass().getName());
        return handle(new InternalServerException());
    }

    private static List<Map<String, String>> violations(Errors bindingResult) {
        List<Map<String, String>> fieldViolations = bindingResult.getFieldErrors().stream()
                .map(error -> Map.of(
                        "field", error.getField(),
                        "reason", isMissingRequiredField(error) ? "필수 입력값입니다." : reason(error)))
                .toList();
        if (!fieldViolations.isEmpty()) {
            return fieldViolations;
        }
        return bindingResult.getGlobalErrors().stream()
                .map(error -> Map.of("field", error.getObjectName(), "reason", reason(error)))
                .toList();
    }

    private static boolean isMissingRequiredField(FieldError error) {
        if (error.getRejectedValue() != null || error.getCodes() == null) {
            return false;
        }
        for (String code : error.getCodes()) {
            if (isRequiredConstraintCode(code)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isRequiredConstraintCode(String code) {
        return code.equals("NotNull")
                || code.endsWith(".NotNull")
                || code.equals("NotBlank")
                || code.endsWith(".NotBlank")
                || code.equals("NotEmpty")
                || code.endsWith(".NotEmpty")
                || code.equals("Required")
                || code.endsWith(".Required");
    }

    private static String reason(ObjectError error) {
        return error.getDefaultMessage() == null
                ? "입력값이 유효하지 않습니다."
                : error.getDefaultMessage();
    }

}
