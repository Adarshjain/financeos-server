package com.financeos.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.llm.provider.OpenAiCompatProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class OpenAiCompatProviderTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    public void setUp() {
        objectMapper = new ObjectMapper();
    }

    @Test
    public void testConvertSchemaForOpenAiAddsAdditionalPropertiesFalse() throws Exception {
        String inputJson = """
                {
                  "type": "object",
                  "properties": {
                    "nested": {
                      "type": "object",
                      "properties": {
                        "val": { "type": "string" }
                      }
                    }
                  }
                }
                """;
        JsonNode inputNode = objectMapper.readTree(inputJson);
        JsonNode converted = OpenAiCompatProvider.convertSchemaForOpenAi(inputNode);

        assertFalse(converted.get("additionalProperties").asBoolean());
        assertFalse(converted.at("/properties/nested/additionalProperties").asBoolean());
    }

    @Test
    public void testStripCodeFences() {
        String input = "```json\n{\"foo\":\"bar\"}\n```";
        assertEquals("{\"foo\":\"bar\"}", OpenAiCompatProvider.stripCodeFences(input));

        String inputNoFences = "{\"foo\":\"bar\"}";
        assertEquals("{\"foo\":\"bar\"}", OpenAiCompatProvider.stripCodeFences(inputNoFences));
    }

    @Test
    public void testBuildRequestBodyJsonSchemaMode() throws Exception {
        LlmProperties.ProviderProperties props = new LlmProperties.ProviderProperties();
        props.setModel("llama-3.3-70b");
        props.setStructuredOutput("json-schema");

        JsonNode schema = objectMapper.readTree("{\"type\":\"object\",\"properties\":{\"ok\":{\"type\":\"boolean\"}}}");
        LlmRequest req = new LlmRequest("test-task", "Hello prompt", schema, 0.0);

        String body = OpenAiCompatProvider.buildRequestBody(req, props, objectMapper);
        JsonNode bodyNode = objectMapper.readTree(body);

        assertEquals("llama-3.3-70b", bodyNode.get("model").asText());
        assertEquals("json_schema", bodyNode.at("/response_format/type").asText());
        assertEquals("test-task", bodyNode.at("/response_format/json_schema/name").asText());
        assertTrue(bodyNode.at("/response_format/json_schema/strict").asBoolean());
        assertFalse(bodyNode.at("/response_format/json_schema/schema/additionalProperties").asBoolean());
    }

    @Test
    public void testBuildRequestBodyJsonObjectMode() throws Exception {
        LlmProperties.ProviderProperties props = new LlmProperties.ProviderProperties();
        props.setModel("llama-3.3-70b-instruct:free");
        props.setStructuredOutput("json-object");

        JsonNode schema = objectMapper.readTree("{\"type\":\"object\",\"properties\":{\"ok\":{\"type\":\"boolean\"}}}");
        LlmRequest req = new LlmRequest("test-task", "Hello prompt", schema, 0.0);

        String body = OpenAiCompatProvider.buildRequestBody(req, props, objectMapper);
        JsonNode bodyNode = objectMapper.readTree(body);

        assertEquals("json_object", bodyNode.at("/response_format/type").asText());
        String content = bodyNode.at("/messages/0/content").asText();
        assertTrue(content.contains("Respond ONLY with a JSON object matching this JSON Schema:"));
        assertTrue(content.contains("Hello prompt"));
    }

    @Test
    public void testParseResponseBodySuccessWithCodeFences() throws Exception {
        String responseBody = """
                {
                  "choices": [
                    {
                      "message": {
                        "content": "```json\\n{\\"ok\\": true}\\n```"
                      }
                    }
                  ]
                }
                """;
        LlmResponse res = OpenAiCompatProvider.parseResponseBody(responseBody, 200, null, "openrouter", "llama-3.3-70b", objectMapper);
        assertEquals("openrouter", res.providerId());
        assertEquals("llama-3.3-70b", res.model());
        assertEquals("{\"ok\": true}", res.jsonText());
    }

    @Test
    public void testParseResponseBodyBadOutput() {
        String responseBody = """
                {
                  "choices": [
                    {
                      "message": {
                        "content": "Not JSON at all"
                      }
                    }
                  ]
                }
                """;
        LlmException ex = assertThrows(LlmException.class, () ->
                OpenAiCompatProvider.parseResponseBody(responseBody, 200, null, "openrouter", "llama-3.3-70b", objectMapper));
        assertEquals(LlmException.Kind.BAD_OUTPUT, ex.getKind());
    }

    @Test
    public void testStripCodeFencesInteriorNotStripped() {
        String inputInterior = "{\"code\":\"some `and` ```more``` backticks\"}";
        assertEquals(inputInterior, OpenAiCompatProvider.stripCodeFences(inputInterior));

        String inputPrefix = "Here is JSON:\n```json\n{\"foo\":\"bar\"}\n```";
        assertEquals(inputPrefix, OpenAiCompatProvider.stripCodeFences(inputPrefix));
    }

    @Test
    public void testConvertSchemaForOpenAiStrictRequiredAndNullableExpansion() throws Exception {
        String inputJson = """
                {
                  "type": "object",
                  "required": ["id"],
                  "properties": {
                    "id": { "type": "string" },
                    "optionalText": { "type": "string" },
                    "optionalEnum": { "type": "string", "enum": ["A", "B"] }
                  }
                }
                """;
        JsonNode inputNode = objectMapper.readTree(inputJson);
        JsonNode converted = OpenAiCompatProvider.convertSchemaForOpenAi(inputNode);

        assertFalse(converted.get("additionalProperties").asBoolean());

        JsonNode requiredArray = converted.get("required");
        assertEquals(3, requiredArray.size());
        java.util.Set<String> reqSet = new java.util.HashSet<>();
        requiredArray.forEach(n -> reqSet.add(n.asText()));
        assertTrue(reqSet.contains("id"));
        assertTrue(reqSet.contains("optionalText"));
        assertTrue(reqSet.contains("optionalEnum"));

        assertEquals("string", converted.at("/properties/id/type").asText());

        JsonNode optTextType = converted.at("/properties/optionalText/type");
        assertTrue(optTextType.isArray());
        assertEquals(2, optTextType.size());
        assertEquals("string", optTextType.get(0).asText());
        assertEquals("null", optTextType.get(1).asText());

        JsonNode optEnumArray = converted.at("/properties/optionalEnum/enum");
        assertTrue(optEnumArray.isArray());
        assertEquals(3, optEnumArray.size());
        assertTrue(optEnumArray.get(2).isNull());
    }
}
