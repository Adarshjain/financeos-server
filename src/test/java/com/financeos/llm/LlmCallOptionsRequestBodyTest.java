package com.financeos.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.financeos.llm.provider.GeminiProvider;
import com.financeos.llm.provider.OpenAiCompatProvider;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The per-model knobs of a custom routing row, as each provider puts them on the wire. */
public class LlmCallOptionsRequestBodyTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private LlmRequest schemaRequest() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        schema.putObject("properties").putObject("status").put("type", "string");
        schema.putArray("required").add("status");
        return new LlmRequest("categorize", "Classify", schema, 0.0);
    }

    private static LlmProperties.ProviderProperties openRouterProps() {
        LlmProperties.ProviderProperties props = new LlmProperties.ProviderProperties();
        props.setStructuredOutput("json-schema");
        props.setModel("z-ai/glm-5.3-flash");
        return props;
    }

    @Test
    public void openAiBodyCarriesEffortCapAndProviderPinWhenSet() throws Exception {
        LlmCallOptions options = new LlmCallOptions(null, "low", 4096, "anthropic");
        JsonNode body = mapper.readTree(OpenAiCompatProvider.buildRequestBody(
                schemaRequest(), openRouterProps(), "anthropic/claude-haiku-5.5", options, mapper));

        assertEquals("anthropic/claude-haiku-5.5", body.get("model").asText());
        assertEquals("low", body.at("/reasoning/effort").asText());
        assertEquals(4096, body.get("max_tokens").asInt());
        assertEquals("anthropic", body.at("/provider/only/0").asText());
        assertFalse(body.at("/provider/allow_fallbacks").asBoolean());
        assertEquals("json_schema", body.at("/response_format/type").asText(), "no override: provider default stays");
    }

    @Test
    public void openAiStructuredOverrideBeatsTheProviderDefault() throws Exception {
        LlmCallOptions options = new LlmCallOptions("json-object", null, null, null);
        JsonNode body = mapper.readTree(OpenAiCompatProvider.buildRequestBody(
                schemaRequest(), openRouterProps(), "foo/bar", options, mapper));

        assertEquals("json_object", body.at("/response_format/type").asText());
        assertTrue(body.at("/messages/0/content").asText().contains("JSON Schema"),
                "json-object mode embeds the schema in the prompt");
    }

    @Test
    public void openAiBodyIsUnchangedWhenNoKnobsAreSet() throws Exception {
        JsonNode withNone = mapper.readTree(OpenAiCompatProvider.buildRequestBody(
                schemaRequest(), openRouterProps(), "foo/bar", LlmCallOptions.NONE, mapper));
        JsonNode legacy = mapper.readTree(OpenAiCompatProvider.buildRequestBody(
                schemaRequest(), openRouterProps(), "foo/bar", mapper));

        assertEquals(legacy, withNone);
        assertFalse(withNone.has("reasoning"));
        assertFalse(withNone.has("max_tokens"));
        assertFalse(withNone.has("provider"));
    }

    @Test
    public void geminiBodyMapsEffortToThinkingLevelAndCapsOutput() throws Exception {
        JsonNode body = mapper.readTree(GeminiProvider.buildRequestBody(
                schemaRequest(), new LlmCallOptions(null, "high", 1000, "ignored-for-gemini"), mapper));

        assertEquals("high", body.at("/generationConfig/thinkingConfig/thinkingLevel").asText());
        assertEquals(1000, body.at("/generationConfig/maxOutputTokens").asInt());
        assertFalse(body.has("provider"));
    }

    @Test
    public void geminiHasNoOffSwitchSoNoneBecomesMinimal() throws Exception {
        JsonNode body = mapper.readTree(GeminiProvider.buildRequestBody(
                schemaRequest(), new LlmCallOptions(null, "none", null, null), mapper));

        assertEquals("minimal", body.at("/generationConfig/thinkingConfig/thinkingLevel").asText());
        assertTrue(body.at("/generationConfig/maxOutputTokens").isMissingNode());
    }

    @Test
    public void geminiBodyIsUnchangedWhenNoKnobsAreSet() throws Exception {
        JsonNode withNone = mapper.readTree(GeminiProvider.buildRequestBody(schemaRequest(), LlmCallOptions.NONE, mapper));
        JsonNode legacy = mapper.readTree(GeminiProvider.buildRequestBody(schemaRequest(), mapper));

        assertEquals(legacy, withNone);
        assertTrue(withNone.at("/generationConfig/thinkingConfig").isMissingNode());
    }

    @Test
    public void optionsTreatBlankAsAbsentAndNullAsNone() {
        assertTrue(LlmCallOptions.of("", " ", null, "").isEmpty());
        assertEquals("low", LlmCallOptions.of(null, " low ", null, null).reasoningEffort());
        assertSame(LlmCallOptions.NONE, LlmCallOptions.orNone(null));
        assertFalse(new LlmCallOptions(null, null, 1, null).isEmpty());
    }
}
