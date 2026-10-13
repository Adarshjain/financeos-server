package com.financeos.domain.llm;

import com.financeos.llm.LlmCallOptions;

import java.util.Locale;
import java.util.Set;

/**
 * A user-typed model on a routing row: provider + model id + optional call knobs, validated once
 * here so the save path and the probe path cannot drift apart. Rows of this kind are stored in
 * {@code llm_task_prefs} with {@code option_id = "custom"} — the menu table is reused rather than
 * duplicated, which also means a custom model is defined per task group (chat and background can
 * carry different reasoning effort for the same model).
 */
public record CustomModelSpec(String provider, String model, LlmCallOptions options) {

    public static final String OPTION_ID = "custom";
    public static final int MAX_MODEL_LENGTH = 255;
    public static final int MAX_PIN_LENGTH = 64;
    public static final int MAX_OUTPUT_TOKENS = 1_000_000;
    public static final Set<String> STRUCTURED_OUTPUTS = Set.of("json-schema", "json-object");
    public static final Set<String> REASONING_EFFORTS = Set.of("none", "minimal", "low", "medium", "high");

    public static boolean isCustomOptionId(String optionId) {
        return optionId != null && OPTION_ID.equalsIgnoreCase(optionId.trim());
    }

    public static CustomModelSpec of(String provider, String model, String structuredOutput, String reasoningEffort,
                                     Integer maxOutputTokens, String providerPin, Set<String> configuredProviders) {
        if (provider == null || provider.isBlank()) {
            throw new IllegalArgumentException("A custom model needs a provider");
        }
        String providerId = provider.trim().toLowerCase(Locale.ROOT);
        if (configuredProviders == null || !configuredProviders.contains(providerId)) {
            throw new IllegalArgumentException("Unknown provider: " + provider);
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("A custom model needs a model id");
        }
        String modelId = model.trim();
        if (modelId.length() > MAX_MODEL_LENGTH) {
            throw new IllegalArgumentException("Model id is too long (max " + MAX_MODEL_LENGTH + " characters)");
        }
        if (modelId.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("Model id cannot contain spaces: " + modelId);
        }
        String structured = lower(structuredOutput);
        if (structured != null && !STRUCTURED_OUTPUTS.contains(structured)) {
            throw new IllegalArgumentException("Structured output must be one of " + STRUCTURED_OUTPUTS);
        }
        String effort = lower(reasoningEffort);
        if (effort != null && !REASONING_EFFORTS.contains(effort)) {
            throw new IllegalArgumentException("Reasoning effort must be one of " + REASONING_EFFORTS);
        }
        if (maxOutputTokens != null && (maxOutputTokens < 1 || maxOutputTokens > MAX_OUTPUT_TOKENS)) {
            throw new IllegalArgumentException("Max output tokens must be between 1 and " + MAX_OUTPUT_TOKENS);
        }
        String pin = providerPin == null || providerPin.isBlank() ? null : providerPin.trim();
        if (pin != null && pin.length() > MAX_PIN_LENGTH) {
            throw new IllegalArgumentException("Provider pin is too long (max " + MAX_PIN_LENGTH + " characters)");
        }
        return new CustomModelSpec(providerId, modelId, LlmCallOptions.of(structured, effort, maxOutputTokens, pin));
    }

    /** Identity of a custom row inside one task group: the same model twice is a duplicate. */
    public String key() {
        return provider + ":" + model.toLowerCase(Locale.ROOT);
    }

    private static String lower(String s) {
        return s == null || s.isBlank() ? null : s.trim().toLowerCase(Locale.ROOT);
    }
}
