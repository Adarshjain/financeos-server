package com.financeos.llm.provider;

import com.financeos.llm.LlmException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

public class LlmHttpSupport {

    private static final Logger log = LoggerFactory.getLogger(LlmHttpSupport.class);

    public static void classifyStatus(int statusCode, String body, Long retryAfter, String providerId) {
        if (statusCode != 200) {
            String truncatedBody = truncate(body, 200);
            if (statusCode == 429 || statusCode >= 500) {
                log.error("Provider {} returned retryable HTTP {}: {}", providerId, statusCode, body);
                throw new LlmException(LlmException.Kind.RETRYABLE, providerId, statusCode, retryAfter, "HTTP " + statusCode + ": " + truncatedBody);
            } else {
                log.error("Provider {} returned fatal HTTP {}: {}", providerId, statusCode, body);
                throw new LlmException(LlmException.Kind.FATAL, providerId, statusCode, retryAfter, "HTTP " + statusCode + ": " + truncatedBody);
            }
        }
    }

    public static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "… (truncated)";
    }

    public static Long parseRetryAfter(HttpResponse<?> response) {
        return response.headers().firstValue("Retry-After")
                .map(val -> {
                    try {
                        return Long.parseLong(val.trim());
                    } catch (NumberFormatException e) {
                        try {
                            ZonedDateTime date = ZonedDateTime.parse(val.trim(), DateTimeFormatter.RFC_1123_DATE_TIME);
                            long diff = Duration.between(Instant.now(), date.toInstant()).getSeconds();
                            return diff > 0 ? diff : 0L;
                        } catch (Exception ex) {
                            return null;
                        }
                    }
                })
                .orElse(null);
    }

    @FunctionalInterface
    public interface HttpExecution {
        HttpResponse<String> execute() throws Exception;
    }

    public static HttpResponse<String> executeAndHandleExceptions(HttpExecution execution, String providerId) {
        try {
            return execution.execute();
        } catch (LlmException e) {
            throw e;
        } catch (IOException e) {
            throw new LlmException(LlmException.Kind.RETRYABLE, providerId, null, null, "IO/Timeout error: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException(LlmException.Kind.FATAL, providerId, null, null, "Request interrupted", e);
        } catch (Exception e) {
            throw new LlmException(LlmException.Kind.FATAL, providerId, null, null, "Unexpected error: " + e.getMessage(), e);
        }
    }
}
