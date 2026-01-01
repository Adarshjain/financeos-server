package com.financeos.gmail.internal;

public class GmailEngineException extends RuntimeException {
    
    private final GmailError errorType;

    public GmailEngineException(GmailError errorType, String message) {
        super(message);
        this.errorType = errorType;
    }

    public GmailEngineException(GmailError errorType, String message, Throwable cause) {
        super(message, cause);
        this.errorType = errorType;
    }

    public GmailError getErrorType() {
        return errorType;
    }
}

