package com.financeos.llm;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.UUID;

public record LlmRequest(UUID userId, String task, String prompt, JsonNode responseSchema, double temperature) {
    public LlmRequest(String task, String prompt, JsonNode responseSchema, double temperature) {
        this(null, task, prompt, responseSchema, temperature);
    }
}
