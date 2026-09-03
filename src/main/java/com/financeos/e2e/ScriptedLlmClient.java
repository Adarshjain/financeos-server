package com.financeos.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.financeos.llm.LlmClient;
import com.financeos.llm.LlmException;
import com.financeos.llm.LlmRequest;
import com.financeos.llm.LlmResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
@Profile("e2e")
@Primary
public class ScriptedLlmClient implements LlmClient, ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ScriptedLlmClient.class);
    private static final int MAX_RECORDED_CALLS = 500;
    private static final int MAX_PROMPT_LENGTH = 4000;
    private static final ObjectMapper mapper = new ObjectMapper();

    public enum Mode { SCHEMA_DEFAULT, STRICT }

    public record Scripted(String json, LlmException.Kind errorKind, String errorMessage) {
        public static Scripted ofJson(String json) {
            return new Scripted(json, null, null);
        }
        public static Scripted ofError(LlmException.Kind kind, String message) {
            return new Scripted(null, kind, message);
        }
        public boolean isError() {
            return errorKind != null;
        }
    }

    public record RecordedCall(String task, UUID userId, String prompt, boolean schemaPresent, Instant timestamp) {}

    private final ConcurrentHashMap<String, ConcurrentLinkedQueue<Scripted>> scriptQueues = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<RecordedCall> recordedCalls = new CopyOnWriteArrayList<>();
    private volatile Mode mode = Mode.SCHEMA_DEFAULT;

    @Override
    public LlmResponse complete(LlmRequest request) {
        // 1. Record the call
        String prompt = request.prompt();
        if (prompt != null && prompt.length() > MAX_PROMPT_LENGTH) {
            prompt = prompt.substring(0, MAX_PROMPT_LENGTH);
        }
        RecordedCall call = new RecordedCall(
                request.task(),
                request.userId(),
                prompt,
                request.responseSchema() != null,
                Instant.now()
        );
        recordedCalls.add(call);
        // Trim oldest if over cap
        while (recordedCalls.size() > MAX_RECORDED_CALLS) {
            recordedCalls.remove(0);
        }

        // 2. Check scripted response
        String task = request.task() != null ? request.task() : "";
        Scripted scripted = pollScript(task);
        if (scripted != null) {
            if (scripted.isError()) {
                throw new LlmException(scripted.errorKind(), "scripted", null, null, scripted.errorMessage());
            }
            return new LlmResponse(scripted.json(), "scripted", "scripted-v1");
        }

        // 3. No script — depends on mode
        if (mode == Mode.STRICT) {
            throw new LlmException(LlmException.Kind.FATAL, "scripted", null, null,
                    "No scripted LLM response for task '" + task + "'");
        }

        // SCHEMA_DEFAULT: synthesize minimal JSON from response schema
        String jsonText = synthesizeFromSchema(request.responseSchema());
        return new LlmResponse(jsonText, "scripted", "scripted-v1");
    }

    private Scripted pollScript(String task) {
        // Try task-specific queue first
        ConcurrentLinkedQueue<Scripted> taskQueue = scriptQueues.get(task);
        if (taskQueue != null) {
            Scripted s = taskQueue.poll();
            if (s != null) return s;
        }
        // Fall back to wildcard
        ConcurrentLinkedQueue<Scripted> wildcardQueue = scriptQueues.get("*");
        if (wildcardQueue != null) {
            return wildcardQueue.poll();
        }
        return null;
    }

    // --- Schema synthesis ---

    String synthesizeFromSchema(JsonNode schema) {
        if (schema == null || schema.isNull() || schema.isMissingNode()) {
            return "{}";
        }
        JsonNode synthesized = synthesizeNode(schema);
        try {
            return mapper.writeValueAsString(synthesized);
        } catch (Exception e) {
            return "{}";
        }
    }

    private JsonNode synthesizeNode(JsonNode schema) {
        if (schema == null || schema.isNull() || schema.isMissingNode()) {
            return mapper.createObjectNode();
        }

        String type = schema.has("type") ? schema.get("type").asText() : "object";

        return switch (type) {
            case "object" -> synthesizeObject(schema);
            case "string" -> synthesizeString(schema);
            case "integer", "number" -> mapper.getNodeFactory().numberNode(0);
            case "boolean" -> mapper.getNodeFactory().booleanNode(false);
            case "array" -> mapper.createArrayNode();
            default -> mapper.createObjectNode();
        };
    }

    private JsonNode synthesizeObject(JsonNode schema) {
        ObjectNode result = mapper.createObjectNode();
        JsonNode properties = schema.get("properties");
        if (properties == null || !properties.isObject()) {
            return result;
        }

        // Determine which properties to include
        Set<String> propertyNames = new LinkedHashSet<>();
        JsonNode required = schema.get("required");
        if (required != null && required.isArray() && !required.isEmpty()) {
            for (JsonNode r : required) {
                propertyNames.add(r.asText());
            }
        } else {
            // No required array → include all properties
            Iterator<String> fieldNames = properties.fieldNames();
            while (fieldNames.hasNext()) {
                propertyNames.add(fieldNames.next());
            }
        }

        for (String propName : propertyNames) {
            JsonNode propSchema = properties.get(propName);
            if (propSchema != null) {
                result.set(propName, synthesizeNode(propSchema));
            }
        }
        return result;
    }

    private JsonNode synthesizeString(JsonNode schema) {
        JsonNode enumNode = schema.get("enum");
        if (enumNode != null && enumNode.isArray() && !enumNode.isEmpty()) {
            return mapper.getNodeFactory().textNode(enumNode.get(0).asText());
        }
        return mapper.getNodeFactory().textNode("");
    }

    // --- Public API for control ---

    public void enqueueScript(String task, Scripted scripted) {
        scriptQueues.computeIfAbsent(task, k -> new ConcurrentLinkedQueue<>()).add(scripted);
    }

    public Map<String, Integer> getQueueSizes() {
        Map<String, Integer> sizes = new LinkedHashMap<>();
        scriptQueues.forEach((task, queue) -> sizes.put(task, queue.size()));
        return sizes;
    }

    public List<RecordedCall> getRecordedCalls(String taskFilter) {
        if (taskFilter == null || taskFilter.isBlank()) {
            return List.copyOf(recordedCalls);
        }
        return recordedCalls.stream()
                .filter(c -> taskFilter.equals(c.task()))
                .toList();
    }

    public void setMode(Mode mode) {
        this.mode = mode;
    }

    public Mode getMode() {
        return mode;
    }

    public void reset() {
        scriptQueues.clear();
        recordedCalls.clear();
        mode = Mode.SCHEMA_DEFAULT;
    }

    // --- ApplicationRunner ---

    @Override
    public void run(ApplicationArguments args) {
        log.info("E2E profile active: ScriptedLlmClient is the primary LlmClient; coverage recording on");
    }
}
