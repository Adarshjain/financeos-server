package com.financeos.api.llm.dto;

public record TestKeyResponse(
        boolean ok,
        String message,
        String error
) {
    public static TestKeyResponse success(String message) {
        return new TestKeyResponse(true, message, null);
    }

    public static TestKeyResponse failure(String error) {
        return new TestKeyResponse(false, null, error);
    }
}
