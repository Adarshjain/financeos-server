package com.financeos.llm;

public interface LlmProvider {
    String id();

    LlmResponse complete(LlmRequest request, String apiKey, String model);

    /**
     * Same call with the per-model knobs of a custom routing row. The default ignores them so a
     * provider (or a test stub) that has no notion of reasoning effort or output caps keeps working.
     */
    default LlmResponse complete(LlmRequest request, String apiKey, String model, LlmCallOptions options) {
        return complete(request, apiKey, model);
    }
}
