package com.financeos.llm;

import com.fasterxml.jackson.databind.JsonNode;

public record LlmRequest(String task, String prompt, JsonNode responseSchema, double temperature) {}
