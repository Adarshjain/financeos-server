package com.financeos.domain.llm;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class CustomModelSpecTest {

    private static final Set<String> PROVIDERS = Set.of("gemini", "openrouter", "groq");

    @Test
    public void normalisesProviderCaseModelWhitespaceAndKnobCase() {
        CustomModelSpec spec = CustomModelSpec.of(" OpenRouter ", " anthropic/claude-haiku-5.5 ", "JSON-Schema", "Low", 4096, " anthropic ", PROVIDERS);

        assertEquals("openrouter", spec.provider());
        assertEquals("anthropic/claude-haiku-5.5", spec.model());
        assertEquals("json-schema", spec.options().structuredOutput());
        assertEquals("low", spec.options().reasoningEffort());
        assertEquals(4096, spec.options().maxOutputTokens());
        assertEquals("anthropic", spec.options().providerPin());
        assertEquals("openrouter:anthropic/claude-haiku-5.5", spec.key());
    }

    @Test
    public void blankKnobsBecomeAbsentRatherThanEmptyStrings() {
        CustomModelSpec spec = CustomModelSpec.of("gemini", "gemini-3.8-flash", "", " ", null, "", PROVIDERS);
        assertTrue(spec.options().isEmpty());
    }

    @Test
    public void keyIgnoresModelCaseSoTheSameModelCannotBeAddedTwice() {
        CustomModelSpec a = CustomModelSpec.of("openrouter", "Foo/Bar", null, null, null, null, PROVIDERS);
        CustomModelSpec b = CustomModelSpec.of("OPENROUTER", "foo/bar", null, null, null, null, PROVIDERS);
        assertEquals(a.key(), b.key());
    }

    @Test
    public void rejectsWhatTheProvidersWouldOnlyRejectLater() {
        assertMessage(() -> CustomModelSpec.of("anthropic", "claude", null, null, null, null, PROVIDERS), "Unknown provider");
        assertMessage(() -> CustomModelSpec.of(null, "claude", null, null, null, null, PROVIDERS), "needs a provider");
        assertMessage(() -> CustomModelSpec.of("gemini", "  ", null, null, null, null, PROVIDERS), "needs a model id");
        assertMessage(() -> CustomModelSpec.of("gemini", "gemini 3.8", null, null, null, null, PROVIDERS), "cannot contain spaces");
        assertMessage(() -> CustomModelSpec.of("gemini", "x".repeat(256), null, null, null, null, PROVIDERS), "too long");
        assertMessage(() -> CustomModelSpec.of("gemini", "m", "xml", null, null, null, PROVIDERS), "Structured output");
        assertMessage(() -> CustomModelSpec.of("gemini", "m", null, "max", null, null, PROVIDERS), "Reasoning effort");
        assertMessage(() -> CustomModelSpec.of("gemini", "m", null, null, 0, null, PROVIDERS), "Max output tokens");
        assertMessage(() -> CustomModelSpec.of("gemini", "m", null, null, 1_000_001, null, PROVIDERS), "Max output tokens");
        assertMessage(() -> CustomModelSpec.of("gemini", "m", null, null, null, "p".repeat(65), PROVIDERS), "Provider pin");
    }

    @Test
    public void customOptionIdIsRecognisedCaseAndSpaceInsensitively() {
        assertTrue(CustomModelSpec.isCustomOptionId("custom"));
        assertTrue(CustomModelSpec.isCustomOptionId(" Custom "));
        assertFalse(CustomModelSpec.isCustomOptionId("gemini-chain"));
        assertFalse(CustomModelSpec.isCustomOptionId(null));
    }

    private static void assertMessage(Runnable call, String fragment) {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, call::run);
        assertTrue(ex.getMessage().contains(fragment), ex.getMessage());
    }
}
