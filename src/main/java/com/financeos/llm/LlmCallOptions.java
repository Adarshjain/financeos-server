package com.financeos.llm;

/**
 * Per-model request knobs a user can set on a custom routing row. Every field is optional; a
 * null means "whatever the provider would do by default", so a menu option (which carries no
 * knobs) is exactly {@link #NONE}.
 *
 * <ul>
 *   <li>{@code structuredOutput}: {@code json-schema} or {@code json-object}, overriding the
 *       catalog/provider default for this one model.</li>
 *   <li>{@code reasoningEffort}: {@code none|minimal|low|medium|high}. Sent as
 *       {@code reasoning.effort} to OpenAI-compatible providers and as
 *       {@code thinkingConfig.thinkingLevel} to Gemini.</li>
 *   <li>{@code maxOutputTokens}: caps the completion (thinking included) so a runaway reasoning
 *       trace cannot eat the task timeout.</li>
 *   <li>{@code providerPin}: an OpenRouter upstream slug; routes only to that upstream with
 *       fallbacks disabled.</li>
 * </ul>
 */
public record LlmCallOptions(
        String structuredOutput,
        String reasoningEffort,
        Integer maxOutputTokens,
        String providerPin
) {
    public static final LlmCallOptions NONE = new LlmCallOptions(null, null, null, null);

    public static LlmCallOptions of(String structuredOutput, String reasoningEffort, Integer maxOutputTokens, String providerPin) {
        return new LlmCallOptions(blankToNull(structuredOutput), blankToNull(reasoningEffort), maxOutputTokens, blankToNull(providerPin));
    }

    public static LlmCallOptions orNone(LlmCallOptions options) {
        return options != null ? options : NONE;
    }

    public boolean isEmpty() {
        return structuredOutput == null && reasoningEffort == null && maxOutputTokens == null && providerPin == null;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
