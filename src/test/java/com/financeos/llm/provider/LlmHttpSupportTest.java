package com.financeos.llm.provider;

import com.financeos.llm.LlmException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class LlmHttpSupportTest {

    @Test
    public void testTruncate() {
        assertNull(LlmHttpSupport.truncate(null, 10));
        assertEquals("hello", LlmHttpSupport.truncate("hello", 10));
        assertEquals("hello… (truncated)", LlmHttpSupport.truncate("hello world", 5));
    }

    @Test
    public void testClassifyStatus200DoesNotThrow() {
        assertDoesNotThrow(() -> LlmHttpSupport.classifyStatus(200, "ok", null, "test-provider"));
    }

    @Test
    public void testClassifyStatus429And500ThrowsRetryable() {
        LlmException ex429 = assertThrows(LlmException.class, () ->
                LlmHttpSupport.classifyStatus(429, "Rate limited", 5L, "test-provider"));
        assertEquals(LlmException.Kind.RETRYABLE, ex429.getKind());
        assertEquals(429, ex429.getStatusCode());
        assertEquals(5L, ex429.getRetryAfterSeconds());

        LlmException ex500 = assertThrows(LlmException.class, () ->
                LlmHttpSupport.classifyStatus(500, "Server error", null, "test-provider"));
        assertEquals(LlmException.Kind.RETRYABLE, ex500.getKind());
        assertEquals(500, ex500.getStatusCode());
    }

    @Test
    public void testClassifyStatus400ThrowsFatalAndTruncatesMessage() {
        String longBody = "x".repeat(300);
        LlmException ex400 = assertThrows(LlmException.class, () ->
                LlmHttpSupport.classifyStatus(400, longBody, null, "test-provider"));
        assertEquals(LlmException.Kind.FATAL, ex400.getKind());
        assertEquals(400, ex400.getStatusCode());
        assertTrue(ex400.getMessage().contains("… (truncated)"));
        assertTrue(ex400.getMessage().length() < 250);
    }

    @Test
    public void testExecuteAndHandleExceptionsTaxonomy() {
        // IOException -> RETRYABLE
        LlmException ioEx = assertThrows(LlmException.class, () ->
                LlmHttpSupport.executeAndHandleExceptions(() -> { throw new IOException("Network failure"); }, "p"));
        assertEquals(LlmException.Kind.RETRYABLE, ioEx.getKind());
        assertTrue(ioEx.getMessage().contains("IO/Timeout error"));

        // InterruptedException -> FATAL and interrupts thread
        LlmException intEx = assertThrows(LlmException.class, () ->
                LlmHttpSupport.executeAndHandleExceptions(() -> { throw new InterruptedException("Sleep interrupted"); }, "p"));
        assertEquals(LlmException.Kind.FATAL, intEx.getKind());
        assertTrue(Thread.currentThread().isInterrupted());
        Thread.interrupted(); // clear flag

        // Runtime/other Exception -> FATAL
        LlmException runEx = assertThrows(LlmException.class, () ->
                LlmHttpSupport.executeAndHandleExceptions(() -> { throw new IllegalStateException("Bad state"); }, "p"));
        assertEquals(LlmException.Kind.FATAL, runEx.getKind());
        assertTrue(runEx.getMessage().contains("Unexpected error"));
    }
}
