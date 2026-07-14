package com.financeos.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.llm.provider.GeminiProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class GeminiProviderTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    public void setUp() {
        objectMapper = new ObjectMapper();
    }

    @Test
    public void testConvertSchemaToGemini() throws Exception {
        String inputJson = """
                {
                  "type": "object",
                  "properties": {
                    "isTransaction": { "type": "boolean" },
                    "amount": { "type": "number", "description": "Total amount" },
                    "direction": { "type": "string", "enum": ["DEBIT", "CREDIT"] }
                  },
                  "required": ["isTransaction"]
                }
                """;
        JsonNode inputNode = objectMapper.readTree(inputJson);
        JsonNode converted = GeminiProvider.convertSchemaToGemini(inputNode, objectMapper);

        assertEquals("OBJECT", converted.get("type").asText());
        assertEquals("BOOLEAN", converted.at("/properties/isTransaction/type").asText());
        assertEquals("NUMBER", converted.at("/properties/amount/type").asText());
        assertEquals("Total amount", converted.at("/properties/amount/description").asText());
        assertEquals("STRING", converted.at("/properties/direction/type").asText());
        assertEquals(2, converted.at("/properties/direction/enum").size());
        assertEquals(1, converted.get("required").size());
    }

    @Test
    public void testParseResponseBodySuccess() throws Exception {
        String responseBody = """
                {
                  "candidates": [
                    {
                      "content": {
                        "parts": [
                          { "text": "{\\"isTransaction\\": true, \\"amount\\": 42.5}" }
                        ]
                      }
                    }
                  ]
                }
                """;
        LlmResponse res = GeminiProvider.parseResponseBody(responseBody, 200, null, "gemini", "gemini-2.5-flash-lite", objectMapper);
        assertEquals("gemini", res.providerId());
        assertEquals("gemini-2.5-flash-lite", res.model());
        assertTrue(res.jsonText().contains("42.5"));
    }

    @Test
    public void testParseResponseBodyRetryableStatus() {
        LlmException ex = assertThrows(LlmException.class, () ->
                GeminiProvider.parseResponseBody("Rate limit exceeded", 429, 5L, "gemini", "gemini-2.5-flash-lite", objectMapper));
        assertEquals(LlmException.Kind.RETRYABLE, ex.getKind());
        assertEquals(429, ex.getStatusCode());
        assertEquals(5L, ex.getRetryAfterSeconds());
    }

    @Test
    public void testParseResponseBodyFatalStatus() {
        LlmException ex = assertThrows(LlmException.class, () ->
                GeminiProvider.parseResponseBody("Bad request", 400, null, "gemini", "gemini-2.5-flash-lite", objectMapper));
        assertEquals(LlmException.Kind.FATAL, ex.getKind());
        assertEquals(400, ex.getStatusCode());
    }

    @Test
    public void testParseResponseBodyBadOutputFormat() {
        String responseBody = """
                {
                  "candidates": [
                    {
                      "content": {
                        "parts": [
                          { "text": "not a valid json string" }
                        ]
                      }
                    }
                  ]
                }
                """;
        LlmException ex = assertThrows(LlmException.class, () ->
                GeminiProvider.parseResponseBody(responseBody, 200, null, "gemini", "gemini-2.5-flash-lite", objectMapper));
        assertEquals(LlmException.Kind.BAD_OUTPUT, ex.getKind());
    }
}
