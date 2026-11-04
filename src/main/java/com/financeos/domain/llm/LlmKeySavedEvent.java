package com.financeos.domain.llm;

import java.util.UUID;

/** An LLM API key was saved for the user (published inside the saving transaction). */
public record LlmKeySavedEvent(UUID userId) {}
