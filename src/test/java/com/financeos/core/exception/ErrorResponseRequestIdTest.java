package com.financeos.core.exception;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

import static org.hamcrest.Matchers.matchesPattern;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ErrorResponseRequestIdTest {

    @RestController
    static class DummyErrorController {
        @GetMapping("/test/not-found")
        public void notFound() {
            throw new ResourceNotFoundException("Account not found");
        }

        @GetMapping("/test/validation")
        public void validation() {
            throw new ValidationException("Invalid input", Map.of("field", "amount"));
        }

        @GetMapping("/test/duplicate")
        public void duplicate() {
            throw new DuplicateResourceException("Account already exists");
        }

        @GetMapping("/test/rate-limited")
        public void rateLimited() {
            throw new TooManyAttemptsException("Too many attempts. Try again later.", 30L);
        }

        @GetMapping("/test/internal-error")
        public void internalError() {
            throw new RuntimeException("Database timeout");
        }
    }

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new DummyErrorController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        MDC.clear();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void testBodyRequestIdEqualsMdcValueAcrossStatusCodes() throws Exception {
        String testRequestId = "req-test-abc-123";
        MDC.put("requestId", testRequestId);

        // 404 NOT_FOUND
        mockMvc.perform(get("/test/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.requestId").value(testRequestId))
                .andExpect(jsonPath("$.errorId").doesNotExist());

        // 400 VALIDATION_ERROR
        mockMvc.perform(get("/test/validation"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details.field").value("amount"))
                .andExpect(jsonPath("$.requestId").value(testRequestId))
                .andExpect(jsonPath("$.errorId").doesNotExist());

        // 409 DUPLICATE
        mockMvc.perform(get("/test/duplicate"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE"))
                .andExpect(jsonPath("$.requestId").value(testRequestId))
                .andExpect(jsonPath("$.errorId").doesNotExist());

        // 429 RATE_LIMITED
        mockMvc.perform(get("/test/rate-limited"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.requestId").value(testRequestId))
                .andExpect(jsonPath("$.errorId").doesNotExist());

        // 500 INTERNAL_ERROR
        mockMvc.perform(get("/test/internal-error"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.requestId").value(testRequestId))
                .andExpect(jsonPath("$.errorId").value(matchesPattern("^[0-9A-HJKMNP-TV-Z]{8}$")));
    }

    @Test
    void testBodyRequestIdIsNullWhenMdcIsEmpty() throws Exception {
        MDC.clear();

        mockMvc.perform(get("/test/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.requestId").doesNotExist());

        mockMvc.perform(get("/test/internal-error"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.requestId").doesNotExist())
                .andExpect(jsonPath("$.errorId").value(matchesPattern("^[0-9A-HJKMNP-TV-Z]{8}$")));
    }

    @Test
    void testCanonical6ArgConstructorDoesNotReadMdc() {
        MDC.put("requestId", "mdc-id-should-be-ignored");
        try {
            GlobalExceptionHandler.ErrorResponse response = new GlobalExceptionHandler.ErrorResponse(
                    "CUSTOM_CODE",
                    "custom message",
                    Map.of("k", "v"),
                    Instant.now(),
                    "ERR12345",
                    "explicit-req-id"
            );

            assertEquals("explicit-req-id", response.requestId());
            assertEquals("ERR12345", response.errorId());

            GlobalExceptionHandler.ErrorResponse responseNullReq = new GlobalExceptionHandler.ErrorResponse(
                    "CUSTOM_CODE",
                    "custom message",
                    Map.of("k", "v"),
                    Instant.now(),
                    null,
                    null
            );
            assertNull(responseNullReq.requestId());
        } finally {
            MDC.clear();
        }
    }
}
