package com.financeos.llm;

public class LlmException extends RuntimeException {
    public enum Kind { RETRYABLE, FATAL, BAD_OUTPUT }

    private final Kind kind;
    private final String providerId;
    private final Integer statusCode;
    private final Long retryAfterSeconds;

    public LlmException(Kind kind, String providerId, Integer statusCode, Long retryAfterSeconds, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.providerId = providerId;
        this.statusCode = statusCode;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public LlmException(Kind kind, String providerId, Integer statusCode, Long retryAfterSeconds, String message) {
        this(kind, providerId, statusCode, retryAfterSeconds, message, null);
    }

    public Kind getKind() {
        return kind;
    }

    public String getProviderId() {
        return providerId;
    }

    public Integer getStatusCode() {
        return statusCode;
    }

    public Long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
