package com.financeos.core.exception;

import com.financeos.core.observability.AccessLogFilter;
import com.financeos.core.observability.Events;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import net.logstash.logback.argument.StructuredArguments;
import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerMapping;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final String CROCKFORD_BASE32 = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern ORA_PATTERN = Pattern.compile("ORA-\\d{5}");

    public record ErrorResponse(
            String code,
            String message,
            Map<String, String> details,
            Instant timestamp,
            String errorId) {

        public ErrorResponse(String code, String message) {
            this(code, message, null, Instant.now(), null);
        }

        public ErrorResponse(String code, String message, Map<String, String> details) {
            this(code, message, details, Instant.now(), null);
        }

        public ErrorResponse(String code, String message, Map<String, String> details, String errorId) {
            this(code, message, details, Instant.now(), errorId);
        }
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleResourceNotFound(ResourceNotFoundException ex, HttpServletRequest request) {
        log4xx("NOT_FOUND", ex.getMessage(), null, request);
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("NOT_FOUND", ex.getMessage()));
    }

    @ExceptionHandler(DuplicateResourceException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateResource(DuplicateResourceException ex, HttpServletRequest request) {
        log4xx("DUPLICATE", ex.getMessage(), null, request);
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("DUPLICATE", ex.getMessage()));
    }

    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<ErrorResponse> handleValidation(ValidationException ex, HttpServletRequest request) {
        log4xx("VALIDATION_ERROR", ex.getMessage(), ex.getDetails(), request);
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("VALIDATION_ERROR", ex.getMessage(), ex.getDetails()));
    }

    @ExceptionHandler(TooManyAttemptsException.class)
    public ResponseEntity<ErrorResponse> handleTooManyAttempts(TooManyAttemptsException ex, HttpServletRequest request) {
        log4xx("RATE_LIMITED", ex.getMessage(), Map.of("retryAfterSeconds", String.valueOf(ex.getRetryAfterSeconds())), request);
        return ResponseEntity
                .status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", String.valueOf(ex.getRetryAfterSeconds()))
                .body(new ErrorResponse("RATE_LIMITED", "Too many attempts. Try again later."));
    }

    @ExceptionHandler(ApiStatusException.class)
    public ResponseEntity<ErrorResponse> handleApiStatus(ApiStatusException ex, HttpServletRequest request) {
        log4xx(ex.getCode(), ex.getMessage(), null, request);
        return ResponseEntity
                .status(ex.getStatus())
                .body(new ErrorResponse(ex.getCode(), ex.getMessage()));
    }

    /**
     * Safety net for {@link ResponseStatusException}, which several controllers throw.
     *
     * Without this, {@code handleGenericException} claims it — {@code Exception} is
     * assignable from it and it is the only match — so every intended 401/403/404/409
     * left here as a 500 with a stack trace in the logs. That also covers Spring's own
     * {@code NoResourceFoundException}, which extends this type.
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> handleResponseStatus(ResponseStatusException ex, HttpServletRequest request) {
        HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        String message = ex.getReason() != null ? ex.getReason() : status.getReasonPhrase();
        String code = status.name();
        if (status.is5xxServerError()) {
            return handleGenericException(ex, request);
        }
        log4xx(code, message, null, request);
        return ResponseEntity
                .status(status)
                .body(new ErrorResponse(code, message));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex, HttpServletRequest request) {
        log4xx("VALIDATION_ERROR", ex.getMessage(), null, request);
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("VALIDATION_ERROR", ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleMethodArgumentNotValid(MethodArgumentNotValidException ex, HttpServletRequest request) {
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach(error -> {
            String fieldName = error instanceof FieldError fe ? fe.getField() : error.getObjectName();
            String errorMessage = error.getDefaultMessage();
            errors.put(fieldName, errorMessage);
        });
        log4xx("VALIDATION_ERROR", "Validation failed", errors, request);
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("VALIDATION_ERROR", "Validation failed", errors));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrityViolation(DataIntegrityViolationException ex, HttpServletRequest request) {
        String constraintName = extractConstraintName(ex);
        String oraCode = extractOraCode(ex);
        Map<String, String> details = new HashMap<>();
        if (constraintName != null) details.put("constraint", constraintName);
        if (oraCode != null) details.put("oraCode", oraCode);

        log4xx("CONFLICT", "Data integrity violation", details, request);
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("CONFLICT", "Data integrity violation: " + (constraintName != null ? constraintName : "constraint violated"), details));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleHttpMessageNotReadable(HttpMessageNotReadableException ex, HttpServletRequest request) {
        Map<String, String> details = new HashMap<>();
        if (ex.getMessage() != null) details.put("parseError", ex.getMessage());
        log4xx("BAD_REQUEST", "Malformed JSON request body", details, request);
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("BAD_REQUEST", "Malformed JSON request body", details));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleMethodArgumentTypeMismatch(MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        Map<String, String> details = new HashMap<>();
        details.put("parameter", ex.getName());
        details.put("requiredType", ex.getRequiredType() != null ? ex.getRequiredType().getSimpleName() : "unknown");
        log4xx("BAD_REQUEST", "Parameter type mismatch", details, request);
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("BAD_REQUEST", "Type mismatch for parameter: " + ex.getName(), details));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleMaxUploadSizeExceeded(MaxUploadSizeExceededException ex, HttpServletRequest request) {
        Map<String, String> details = new HashMap<>();
        details.put("maxSize", String.valueOf(ex.getMaxUploadSize()));
        log4xx("PAYLOAD_TOO_LARGE", "Upload size limit exceeded", details, request);
        return ResponseEntity
                .status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(new ErrorResponse("PAYLOAD_TOO_LARGE", "Uploaded file exceeds maximum allowed size", details));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        Map<String, String> details = Map.of("reason", "insufficient-authority");
        log4xx("FORBIDDEN", "Access denied", details, request);
        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .body(new ErrorResponse("FORBIDDEN", "Access denied: insufficient authority", details));
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorResponse> handleAuthenticationException(AuthenticationException ex, HttpServletRequest request) {
        Map<String, String> details = Map.of("reason", "authentication-failed");
        log4xx("UNAUTHORIZED", ex.getMessage() != null ? ex.getMessage() : "Authentication failed", details, request);
        return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .body(new ErrorResponse("UNAUTHORIZED", ex.getMessage() != null ? ex.getMessage() : "Authentication failed", details));
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> handleOptimisticLockingFailure(OptimisticLockingFailureException ex, HttpServletRequest request) {
        Map<String, String> details = new HashMap<>();
        details.put("reason", "concurrent-update-conflict");
        log4xx("CONFLICT", "Resource was updated by another request", details, request);
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("CONFLICT", "Resource was updated by another request. Please retry.", details));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(Exception ex, HttpServletRequest request) {
        String errorId = generateErrorId();
        String oraCode = extractOraCode(ex);

        request.setAttribute(AccessLogFilter.ERROR_CODE_ATTR, "INTERNAL_ERROR");
        request.setAttribute(AccessLogFilter.ERROR_ID_ATTR, errorId);

        String routeAttr = (String) request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        String route = routeAttr != null ? routeAttr : "UNMATCHED";
        String requestId = MDC.get("requestId");
        String userId = MDC.get("userId");

        log.error("Unexpected 5xx: errorId={}, exceptionClass={}",
                StructuredArguments.value("errorId", errorId),
                StructuredArguments.value("exceptionClass", ex.getClass().getName()),
                StructuredArguments.keyValue("event", Events.REQUEST_FAILED),
                StructuredArguments.keyValue("route", route),
                StructuredArguments.keyValue("requestId", requestId),
                StructuredArguments.keyValue("userId", userId),
                StructuredArguments.keyValue("oraCode", oraCode),
                ex);

        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("INTERNAL_ERROR", "An unexpected error occurred", null, errorId));
    }

    private void log4xx(String code, String message, Map<String, String> details, HttpServletRequest request) {
        request.setAttribute(AccessLogFilter.ERROR_CODE_ATTR, code);
        String routeAttr = (String) request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        String route = routeAttr != null ? routeAttr : "UNMATCHED";
        String requestId = MDC.get("requestId");
        String userId = MDC.get("userId");

        log.warn("Request failed (4xx): code={}, message={}", code, message,
                StructuredArguments.keyValue("event", Events.REQUEST_FAILED),
                StructuredArguments.keyValue("code", code),
                StructuredArguments.keyValue("route", route),
                StructuredArguments.keyValue("requestId", requestId),
                StructuredArguments.keyValue("userId", userId),
                StructuredArguments.keyValue("details", details)
        );
    }

    public static String generateErrorId() {
        StringBuilder sb = new StringBuilder(8);
        for (int i = 0; i < 8; i++) {
            sb.append(CROCKFORD_BASE32.charAt(RANDOM.nextInt(CROCKFORD_BASE32.length())));
        }
        return sb.toString();
    }

    public static String extractOraCode(Throwable t) {
        Throwable current = t;
        while (current != null) {
            String msg = current.getMessage();
            if (msg != null) {
                Matcher matcher = ORA_PATTERN.matcher(msg);
                if (matcher.find()) {
                    return matcher.group();
                }
            }
            current = current.getCause();
        }
        return null;
    }

    private String extractConstraintName(Throwable t) {
        Throwable current = t;
        while (current != null) {
            if (current instanceof ConstraintViolationException cve) {
                return cve.getConstraintName();
            }
            current = current.getCause();
        }
        return null;
    }
}
