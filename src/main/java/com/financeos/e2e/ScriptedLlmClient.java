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

    /**
     * One scripted answer. {@code promptContains}, when set, makes the entry keyed instead of FIFO:
     * it is served to the first call whose prompt contains that substring, whatever its position in
     * the queue. Keyed entries let a test script several parallel-order-independent answers (e.g. the
     * Gmail sync drains discovered messages in an order the test cannot control).
     */
    public record Scripted(String json, LlmException.Kind errorKind, String errorMessage, long delayMs,
                           String promptContains) {
        public Scripted(String json, LlmException.Kind errorKind, String errorMessage, long delayMs) {
            this(json, errorKind, errorMessage, delayMs, null);
        }
        public static Scripted ofJson(String json) {
            return new Scripted(json, null, null, 0L);
        }
        public static Scripted ofJson(String json, long delayMs) {
            return new Scripted(json, null, null, delayMs);
        }
        public static Scripted ofError(LlmException.Kind kind, String message) {
            return new Scripted(null, kind, message, 0L);
        }
        public static Scripted ofError(LlmException.Kind kind, String message, long delayMs) {
            return new Scripted(null, kind, message, delayMs);
        }
        public Scripted keyedBy(String promptSubstring) {
            return new Scripted(json, errorKind, errorMessage, delayMs, promptSubstring);
        }
        public boolean isError() {
            return errorKind != null;
        }
        public boolean isKeyed() {
            return promptContains != null && !promptContains.isBlank();
        }
    }

    public record RecordedCall(String task, UUID userId, String prompt, boolean schemaPresent, Instant timestamp) {}

    /**
     * Scripts, mode and recorded calls are scoped per user (the E2E suite runs one user per worker),
     * with a global scope as fallback for callers that have no user (unit tests, LlmKeyService "test").
     */
    static final String GLOBAL_SCOPE = "__global__";
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, ConcurrentLinkedQueue<Scripted>>> scriptQueues = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<RecordedCall> recordedCalls = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<String, Mode> modes = new ConcurrentHashMap<>();

    private static String scope(UUID userId) {
        return userId == null ? GLOBAL_SCOPE : userId.toString();
    }

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
        Scripted scripted = pollScript(request.userId(), task, request.prompt());
        if (scripted != null) {
            if (scripted.delayMs() > 0) {
                try {
                    Thread.sleep(scripted.delayMs());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new LlmException(LlmException.Kind.FATAL, "scripted", null, null,
                            "Interrupted during scripted delay");
                }
            }
            if (scripted.isError()) {
                throw new LlmException(scripted.errorKind(), "scripted", null, null, scripted.errorMessage());
            }
            return new LlmResponse(scripted.json(), "scripted", "scripted-v1");
        }

        // 3. No script — depends on mode
        if (getMode(request.userId()) == Mode.STRICT) {
            throw new LlmException(LlmException.Kind.FATAL, "scripted", null, null,
                    "No scripted LLM response for task '" + task + "'");
        }

        // SCHEMA_DEFAULT: synthesize minimal JSON from response schema
        String jsonText = synthesizeFromSchema(request.responseSchema());
        return new LlmResponse(jsonText, "scripted", "scripted-v1");
    }

    private Scripted pollScript(UUID userId, String task, String prompt) {
        Scripted s = pollFromScope(scope(userId), task, prompt);
        if (s == null && userId != null) {
            s = pollFromScope(GLOBAL_SCOPE, task, prompt);
        }
        return s;
    }

    private Scripted pollFromScope(String scopeKey, String task, String prompt) {
        ConcurrentHashMap<String, ConcurrentLinkedQueue<Scripted>> queues = scriptQueues.get(scopeKey);
        if (queues == null) {
            return null;
        }
        ConcurrentLinkedQueue<Scripted> taskQueue = queues.get(task);
        if (taskQueue != null) {
            Scripted s = takeMatching(taskQueue, prompt);
            if (s != null) return s;
        }
        ConcurrentLinkedQueue<Scripted> wildcardQueue = queues.get("*");
        return wildcardQueue != null ? takeMatching(wildcardQueue, prompt) : null;
    }

    /**
     * Keyed entries win when their substring occurs in the prompt; otherwise the oldest un-keyed
     * entry is served. A keyed entry whose key never matches stays queued (visible in queue sizes).
     */
    private static Scripted takeMatching(ConcurrentLinkedQueue<Scripted> queue, String prompt) {
        String haystack = prompt == null ? "" : prompt;
        for (Scripted candidate : queue) {
            if (candidate.isKeyed() && haystack.contains(candidate.promptContains())) {
                if (queue.remove(candidate)) {
                    return candidate;
                }
            }
        }
        for (Scripted candidate : queue) {
            if (!candidate.isKeyed()) {
                if (queue.remove(candidate)) {
                    return candidate;
                }
            }
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

    // --- Public API for control (no-arg / null-user variants act on the global scope) ---

    public void enqueueScript(String task, Scripted scripted) {
        enqueueScript(null, task, scripted);
    }

    public void enqueueScript(UUID userId, String task, Scripted scripted) {
        scriptQueues.computeIfAbsent(scope(userId), k -> new ConcurrentHashMap<>())
                .computeIfAbsent(task, k -> new ConcurrentLinkedQueue<>())
                .add(scripted);
    }

    public Map<String, Integer> getQueueSizes() {
        return getQueueSizes(null);
    }

    public Map<String, Integer> getQueueSizes(UUID userId) {
        Map<String, Integer> sizes = new LinkedHashMap<>();
        ConcurrentHashMap<String, ConcurrentLinkedQueue<Scripted>> queues = scriptQueues.get(scope(userId));
        if (queues != null) {
            queues.forEach((task, queue) -> sizes.put(task, queue.size()));
        }
        return sizes;
    }

    /** All recorded calls (any user), optionally filtered by task. */
    public List<RecordedCall> getRecordedCalls(String taskFilter) {
        return getRecordedCalls(taskFilter, null);
    }

    /** Recorded calls for one user (null = any user), optionally filtered by task. */
    public List<RecordedCall> getRecordedCalls(String taskFilter, UUID userId) {
        return recordedCalls.stream()
                .filter(c -> userId == null || userId.equals(c.userId()))
                .filter(c -> taskFilter == null || taskFilter.isBlank() || taskFilter.equals(c.task()))
                .toList();
    }

    public void setMode(Mode mode) {
        setMode(null, mode);
    }

    public void setMode(UUID userId, Mode mode) {
        modes.put(scope(userId), mode);
    }

    public Mode getMode() {
        return getMode(null);
    }

    /** A user's mode, falling back to the global mode, then SCHEMA_DEFAULT. */
    public Mode getMode(UUID userId) {
        Mode m = modes.get(scope(userId));
        if (m == null && userId != null) {
            m = modes.get(GLOBAL_SCOPE);
        }
        return m != null ? m : Mode.SCHEMA_DEFAULT;
    }

    /** Clears everything, every scope. */
    public void reset() {
        scriptQueues.clear();
        recordedCalls.clear();
        modes.clear();
    }

    /** Clears one user's scripts, mode and recorded calls only. */
    public void reset(UUID userId) {
        if (userId == null) {
            reset();
            return;
        }
        scriptQueues.remove(scope(userId));
        modes.remove(scope(userId));
        recordedCalls.removeIf(c -> userId.equals(c.userId()));
    }

    // --- ApplicationRunner ---

    @Override
    public void run(ApplicationArguments args) {
        log.info("E2E profile active: ScriptedLlmClient is the primary LlmClient; coverage recording on");
    }
}
