package com.financeos.llm;

public class LlmException extends RuntimeException {
    public enum Kind { RETRYABLE, FATAL, BAD_OUTPUT, NO_KEYS }

    private final Kind kind;
    private final String providerId;
    private final Integer statusCode;
    private final Long retryAfterSeconds;
    private final String responseBody;

    public LlmException(Kind kind, String providerId, Integer statusCode, Long retryAfterSeconds, String message, String responseBody, Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.providerId = providerId;
        this.statusCode = statusCode;
        this.retryAfterSeconds = retryAfterSeconds;
        this.responseBody = responseBody != null && responseBody.length() > 4000
                ? responseBody.substring(0, 4000)
                : responseBody;
    }

    public LlmException(Kind kind, String providerId, Integer statusCode, Long retryAfterSeconds, String message, String responseBody) {
        this(kind, providerId, statusCode, retryAfterSeconds, message, responseBody, null);
    }

    public LlmException(Kind kind, String providerId, Integer statusCode, Long retryAfterSeconds, String message, Throwable cause) {
        this(kind, providerId, statusCode, retryAfterSeconds, message, null, cause);
    }

    public LlmException(Kind kind, String providerId, Integer statusCode, Long retryAfterSeconds, String message) {
        this(kind, providerId, statusCode, retryAfterSeconds, message, null, null);
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

    public String getResponseBody() {
        return responseBody;
    }
}
