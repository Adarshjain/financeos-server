package com.financeos.api.llm.dto;

import org.springframework.lang.Nullable;

public record TestKeyResponse(
        boolean ok,
        @Nullable String message,
        @Nullable String error
) {
    public static TestKeyResponse success(String message) {
        return new TestKeyResponse(true, message, null);
    }

    public static TestKeyResponse failure(String error) {
        return new TestKeyResponse(false, null, error);
    }
}
