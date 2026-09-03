package com.financeos.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.financeos.llm.LlmException;
import com.financeos.llm.LlmRequest;
import com.financeos.llm.LlmResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ScriptedLlmClientTest {

    private ScriptedLlmClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        client = new ScriptedLlmClient();
    }

    @Test
    void fifoPerTask() {
        client.enqueueScript("categorize", ScriptedLlmClient.Scripted.ofJson("{\"first\": true}"));
        client.enqueueScript("categorize", ScriptedLlmClient.Scripted.ofJson("{\"second\": true}"));

        LlmResponse r1 = client.complete(new LlmRequest("categorize", "prompt", null, 0.0));
        LlmResponse r2 = client.complete(new LlmRequest("categorize", "prompt", null, 0.0));

        assertEquals("{\"first\": true}", r1.jsonText());
        assertEquals("{\"second\": true}", r2.jsonText());
        assertEquals("scripted", r1.providerId());
        assertEquals("scripted-v1", r1.model());
    }

    @Test
    void wildcardFallback() {
        client.enqueueScript("*", ScriptedLlmClient.Scripted.ofJson("{\"wildcard\": true}"));

        LlmResponse r = client.complete(new LlmRequest("categorize", "prompt", null, 0.0));
        assertEquals("{\"wildcard\": true}", r.jsonText());
    }

    @Test
    void taskQueueTakesPrecedenceOverWildcard() {
        client.enqueueScript("*", ScriptedLlmClient.Scripted.ofJson("{\"wildcard\": true}"));
        client.enqueueScript("categorize", ScriptedLlmClient.Scripted.ofJson("{\"specific\": true}"));

        LlmResponse r = client.complete(new LlmRequest("categorize", "prompt", null, 0.0));
        assertEquals("{\"specific\": true}", r.jsonText());
    }

    @Test
    void scriptedErrorThrowsCorrectKind() {
        client.enqueueScript("test", ScriptedLlmClient.Scripted.ofError(LlmException.Kind.FATAL, "boom"));

        LlmException ex = assertThrows(LlmException.class,
                () -> client.complete(new LlmRequest("test", "prompt", null, 0.0)));
        assertEquals(LlmException.Kind.FATAL, ex.getKind());
        assertEquals("boom", ex.getMessage());
        assertEquals("scripted", ex.getProviderId());
    }

    @Test
    void scriptedErrorRetryableKind() {
        client.enqueueScript("test", ScriptedLlmClient.Scripted.ofError(LlmException.Kind.RETRYABLE, "try again"));

        LlmException ex = assertThrows(LlmException.class,
                () -> client.complete(new LlmRequest("test", "prompt", null, 0.0)));
        assertEquals(LlmException.Kind.RETRYABLE, ex.getKind());
    }

    @Test
    void strictModeThrowsWithTaskName() {
        client.setMode(ScriptedLlmClient.Mode.STRICT);

        LlmException ex = assertThrows(LlmException.class,
                () -> client.complete(new LlmRequest("data-chat", "prompt", null, 0.0)));
        assertEquals(LlmException.Kind.FATAL, ex.getKind());
        assertTrue(ex.getMessage().contains("data-chat"));
    }

    @Test
    void schemaDefaultSynthesizesRequiredOnlyObject() throws Exception {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("name").put("type", "string");
        props.putObject("age").put("type", "integer");
        props.putObject("optional_field").put("type", "string");
        schema.putArray("required").add("name").add("age");

        LlmResponse r = client.complete(new LlmRequest("test", "prompt", schema, 0.0));
        JsonNode result = mapper.readTree(r.jsonText());
        assertTrue(result.has("name"));
        assertTrue(result.has("age"));
        assertFalse(result.has("optional_field"));
        assertEquals("", result.get("name").asText());
        assertEquals(0, result.get("age").asInt());
    }

    @Test
    void schemaDefaultHonoursEnum() throws Exception {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        ObjectNode statusProp = props.putObject("status");
        statusProp.put("type", "string");
        statusProp.putArray("enum").add("ACTIVE").add("INACTIVE");

        LlmResponse r = client.complete(new LlmRequest("test", "prompt", schema, 0.0));
        JsonNode result = mapper.readTree(r.jsonText());
        assertEquals("ACTIVE", result.get("status").asText());
    }

    @Test
    void schemaDefaultHandlesNestedObjects() throws Exception {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        ObjectNode innerSchema = props.putObject("address");
        innerSchema.put("type", "object");
        ObjectNode innerProps = innerSchema.putObject("properties");
        innerProps.putObject("city").put("type", "string");
        innerProps.putObject("zip").put("type", "integer");

        LlmResponse r = client.complete(new LlmRequest("test", "prompt", schema, 0.0));
        JsonNode result = mapper.readTree(r.jsonText());
        assertTrue(result.has("address"));
        assertTrue(result.get("address").has("city"));
        assertEquals("", result.get("address").get("city").asText());
        assertEquals(0, result.get("address").get("zip").asInt());
    }

    @Test
    void schemaDefaultHandlesNullSchema() {
        LlmResponse r = client.complete(new LlmRequest("test", "prompt", null, 0.0));
        assertEquals("{}", r.jsonText());
    }

    @Test
    void schemaDefaultHandlesAllTypes() throws Exception {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("s").put("type", "string");
        props.putObject("i").put("type", "integer");
        props.putObject("n").put("type", "number");
        props.putObject("b").put("type", "boolean");
        props.putObject("a").put("type", "array");

        LlmResponse r = client.complete(new LlmRequest("test", "prompt", schema, 0.0));
        JsonNode result = mapper.readTree(r.jsonText());
        assertEquals("", result.get("s").asText());
        assertEquals(0, result.get("i").asInt());
        assertEquals(0, result.get("n").asInt());
        assertFalse(result.get("b").asBoolean());
        assertTrue(result.get("a").isArray());
        assertEquals(0, result.get("a").size());
    }

    @Test
    void callRecordingCapsAt500() {
        for (int i = 0; i < 510; i++) {
            client.complete(new LlmRequest("test", "prompt-" + i, null, 0.0));
        }
        assertEquals(500, client.getRecordedCalls(null).size());
    }

    @Test
    void callRecordingTruncatesPrompt() {
        String longPrompt = "x".repeat(5000);
        client.complete(new LlmRequest("test", longPrompt, null, 0.0));

        var calls = client.getRecordedCalls("test");
        assertEquals(1, calls.size());
        assertEquals(4000, calls.get(0).prompt().length());
    }

    @Test
    void callRecordingFiltersByTask() {
        client.complete(new LlmRequest("a", "p", null, 0.0));
        client.complete(new LlmRequest("b", "p", null, 0.0));
        client.complete(new LlmRequest("a", "p", null, 0.0));

        assertEquals(2, client.getRecordedCalls("a").size());
        assertEquals(1, client.getRecordedCalls("b").size());
        assertEquals(3, client.getRecordedCalls(null).size());
    }

    @Test
    void resetClearsEverything() {
        client.enqueueScript("test", ScriptedLlmClient.Scripted.ofJson("{}"));
        client.complete(new LlmRequest("other", "p", null, 0.0));
        client.setMode(ScriptedLlmClient.Mode.STRICT);

        client.reset();

        assertEquals(ScriptedLlmClient.Mode.SCHEMA_DEFAULT, client.getMode());
        assertTrue(client.getRecordedCalls(null).isEmpty());
        assertTrue(client.getQueueSizes().isEmpty());
    }
}
