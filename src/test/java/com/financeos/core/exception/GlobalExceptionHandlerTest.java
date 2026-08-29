package com.financeos.core.exception;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;

import org.springframework.web.method.annotation.ExceptionHandlerMethodResolver;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;

class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;
    private MockHttpServletRequest request;
    private Logger logger;
    private ListAppender<ILoggingEvent> listAppender;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
        request = new MockHttpServletRequest();

        logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        listAppender = new ListAppender<>();
        listAppender.start();
        logger.addAppender(listAppender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(listAppender);
    }

    @Test
    void testGeneric5xxExceptionReturnsErrorIdAndRecordsThrowableProxy() {
        RuntimeException ex = new RuntimeException("Database connection timeout");
        ResponseEntity<GlobalExceptionHandler.ErrorResponse> response = handler.handleGenericException(ex, request);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("INTERNAL_ERROR", response.getBody().code());
        assertNotNull(response.getBody().errorId());
        assertEquals(8, response.getBody().errorId().length());
        assertTrue(response.getBody().errorId().matches("^[0-9A-Z]{8}$"));

        assertEquals(1, listAppender.list.size());
        ILoggingEvent event = listAppender.list.get(0);
        assertNotNull(event.getThrowableProxy(), "5xx log event must capture throwableProxy");
        assertEquals(RuntimeException.class.getName(), event.getThrowableProxy().getClassName());
    }

    @Test
    void test4xxExceptionReturnsNullErrorIdAndNoThrowableProxy() {
        ResourceNotFoundException ex = new ResourceNotFoundException("Account not found");
        ResponseEntity<GlobalExceptionHandler.ErrorResponse> response = handler.handleResourceNotFound(ex, request);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("NOT_FOUND", response.getBody().code());
        assertNull(response.getBody().errorId());

        assertEquals(1, listAppender.list.size());
        ILoggingEvent event = listAppender.list.get(0);
        assertNull(event.getThrowableProxy(), "4xx log event must not capture stack trace / throwableProxy");
    }

    @Test
    void testOraCodeExtractionFromExceptionCauseChain() {
        RuntimeException cause = new RuntimeException("ORA-00001: unique constraint (FINANCEOS.UK_ACC_NAME) violated");
        DataIntegrityViolationException ex = new DataIntegrityViolationException("Could not execute statement", cause);

        String oraCode = GlobalExceptionHandler.extractOraCode(ex);
        assertEquals("ORA-00001", oraCode);

        ResponseEntity<GlobalExceptionHandler.ErrorResponse> response = handler.handleDataIntegrityViolation(ex, request);
        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("ORA-00001", response.getBody().details().get("oraCode"));
    }

    @Test
    void testHttpMessageNotReadableException() {
        HttpMessageNotReadableException ex = new HttpMessageNotReadableException("JSON parse error: Unexpected character");
        ResponseEntity<GlobalExceptionHandler.ErrorResponse> response = handler.handleHttpMessageNotReadable(ex, request);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("BAD_REQUEST", response.getBody().code());
    }

    @Test
    void testMethodArgumentTypeMismatchException() {
        MethodArgumentTypeMismatchException ex = new MethodArgumentTypeMismatchException(
                "invalid", Integer.class, "id", null, new IllegalArgumentException());
        ResponseEntity<GlobalExceptionHandler.ErrorResponse> response = handler.handleMethodArgumentTypeMismatch(ex, request);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("id", response.getBody().details().get("parameter"));
        assertEquals("Integer", response.getBody().details().get("requiredType"));
    }

    @Test
    void testMaxUploadSizeExceededException() {
        MaxUploadSizeExceededException ex = new MaxUploadSizeExceededException(10485760);
        ResponseEntity<GlobalExceptionHandler.ErrorResponse> response = handler.handleMaxUploadSizeExceeded(ex, request);

        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, response.getStatusCode());
        assertEquals("PAYLOAD_TOO_LARGE", response.getBody().code());
    }

    @Test
    void testAccessDeniedException() {
        AccessDeniedException ex = new AccessDeniedException("Access is denied");
        ResponseEntity<GlobalExceptionHandler.ErrorResponse> response = handler.handleAccessDenied(ex, request);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertEquals("FORBIDDEN", response.getBody().code());
        assertEquals("insufficient-authority", response.getBody().details().get("reason"));
    }

    @Test
    void testAuthenticationException() {
        BadCredentialsException ex = new BadCredentialsException("Bad credentials");
        ResponseEntity<GlobalExceptionHandler.ErrorResponse> response = handler.handleAuthenticationException(ex, request);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertEquals("UNAUTHORIZED", response.getBody().code());
    }

    @Test
    void testOptimisticLockingFailureException() {
        OptimisticLockingFailureException ex = new OptimisticLockingFailureException("Row updated by another transaction");
        ResponseEntity<GlobalExceptionHandler.ErrorResponse> response = handler.handleOptimisticLockingFailure(ex, request);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals("CONFLICT", response.getBody().code());
    }

    @Test
    void testApiStatusExceptionCarriesItsOwnStatusAndCode() {
        ApiStatusException ex = new ApiStatusException(HttpStatus.CONFLICT, "ACCOUNT_DELETE_BUSY", "Still finishing.");
        ResponseEntity<GlobalExceptionHandler.ErrorResponse> response = handler.handleApiStatus(ex, request);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals("ACCOUNT_DELETE_BUSY", response.getBody().code());
        assertEquals("Still finishing.", response.getBody().message());
    }

    @Test
    void testResponseStatusExceptionKeepsItsStatusInsteadOfBecoming500() {
        ResponseStatusException ex = new ResponseStatusException(HttpStatus.FORBIDDEN, "Not your resource");
        ResponseEntity<GlobalExceptionHandler.ErrorResponse> response = handler.handleResponseStatus(ex, request);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertEquals("FORBIDDEN", response.getBody().code());
        assertEquals("Not your resource", response.getBody().message());
    }

    @Test
    void testResponseStatusExceptionWith5xxStillGetsAnErrorId() {
        ResponseStatusException ex = new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "upstream down");
        ResponseEntity<GlobalExceptionHandler.ErrorResponse> response = handler.handleResponseStatus(ex, request);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertNotNull(response.getBody().errorId());
    }

    /**
     * The handlers above are called directly, which proves nothing about which one Spring
     * would actually pick. It used to pick {@code handleGenericException} for every
     * {@link ResponseStatusException} — {@code Exception} is assignable from it and was the
     * only match — so intended 401/403/404/409 responses all left as 500s. This asserts the
     * dispatch itself.
     */
    @Test
    void testDispatchPicksTheDedicatedHandlerNotTheGeneric5xxOne() {
        ExceptionHandlerMethodResolver resolver = new ExceptionHandlerMethodResolver(GlobalExceptionHandler.class);

        assertEquals("handleResponseStatus",
                resolver.resolveMethod(new ResponseStatusException(HttpStatus.FORBIDDEN, "nope")).getName());
        assertEquals("handleApiStatus",
                resolver.resolveMethod(new ApiStatusException(HttpStatus.CONFLICT, "BUSY", "busy")).getName());
        assertEquals("handleGenericException",
                resolver.resolveMethod(new RuntimeException("boom")).getName());
    }
}
