package com.financeos.e2e;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.llm.LlmException;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@Profile("e2e")
@RequestMapping("/api/e2e")
public class E2eControlController {

    private final ScriptedLlmClient scriptedLlmClient;
    private final CoverageRegistry coverageRegistry;
    private final ObjectMapper objectMapper;

    public E2eControlController(ScriptedLlmClient scriptedLlmClient,
                                 CoverageRegistry coverageRegistry,
                                 ObjectMapper objectMapper) {
        this.scriptedLlmClient = scriptedLlmClient;
        this.coverageRegistry = coverageRegistry;
        this.objectMapper = objectMapper;
    }

    // --- DTOs ---

    public record ScriptRequest(String task, List<ScriptResponseEntry> responses) {}

    public record ScriptResponseEntry(JsonNode json, ScriptErrorEntry error) {}

    public record ScriptErrorEntry(String kind, String message) {}

    public record ScriptResult(Map<String, Integer> queued) {}

    public record ModeRequest(String mode) {}

    public record ModeResponse(String mode) {}

    public record CallEntry(String task, UUID userId, String prompt, boolean schemaPresent, Instant timestamp) {}

    public record CallsResponse(List<CallEntry> calls) {}

    public record CoverageHit(String method, String pattern, int ok, int clientError, int serverError) {}

    public record CoverageResponse(List<CoverageHit> hits) {}

    // --- LLM Script Endpoints ---

    @PostMapping("/llm/script")
    public ResponseEntity<ScriptResult> enqueueScript(@RequestBody ScriptRequest request) {
        if (request.task() == null || request.task().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "'task' is required");
        }
        if (request.responses() == null || request.responses().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "'responses' must not be empty");
        }

        for (ScriptResponseEntry entry : request.responses()) {
            if (entry.json() != null && entry.error() != null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Each response must have either 'json' or 'error', not both");
            }
            if (entry.json() == null && entry.error() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Each response must have either 'json' or 'error'");
            }

            if (entry.error() != null) {
                ScriptErrorEntry err = entry.error();
                if (err.kind() == null || err.kind().isBlank()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "error.kind is required");
                }
                LlmException.Kind kind;
                try {
                    kind = LlmException.Kind.valueOf(err.kind().toUpperCase());
                } catch (IllegalArgumentException e) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Invalid error kind '" + err.kind() + "'. Valid: RETRYABLE, FATAL, BAD_OUTPUT, NO_KEYS");
                }
                scriptedLlmClient.enqueueScript(request.task(),
                        ScriptedLlmClient.Scripted.ofError(kind, err.message()));
            } else {
                // json may be object or string
                String jsonText;
                if (entry.json().isTextual()) {
                    jsonText = entry.json().asText();
                } else {
                    try {
                        jsonText = objectMapper.writeValueAsString(entry.json());
                    } catch (JsonProcessingException e) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Failed to serialize json: " + e.getMessage());
                    }
                }
                scriptedLlmClient.enqueueScript(request.task(),
                        ScriptedLlmClient.Scripted.ofJson(jsonText));
            }
        }

        return ResponseEntity.ok(new ScriptResult(scriptedLlmClient.getQueueSizes()));
    }

    @PutMapping("/llm/mode")
    public ResponseEntity<ModeResponse> setMode(@RequestBody ModeRequest request) {
        if (request.mode() == null || request.mode().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "'mode' is required");
        }
        ScriptedLlmClient.Mode mode;
        try {
            mode = ScriptedLlmClient.Mode.valueOf(request.mode().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Invalid mode '" + request.mode() + "'. Valid: STRICT, SCHEMA_DEFAULT");
        }
        scriptedLlmClient.setMode(mode);
        return ResponseEntity.ok(new ModeResponse(mode.name()));
    }

    @GetMapping("/llm/calls")
    public ResponseEntity<CallsResponse> getCalls(@RequestParam(required = false) String task) {
        List<ScriptedLlmClient.RecordedCall> calls = scriptedLlmClient.getRecordedCalls(task);
        List<CallEntry> entries = calls.stream()
                .map(c -> new CallEntry(c.task(), c.userId(), c.prompt(), c.schemaPresent(), c.timestamp()))
                .toList();
        return ResponseEntity.ok(new CallsResponse(entries));
    }

    @DeleteMapping("/llm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetLlm() {
        scriptedLlmClient.reset();
    }

    // --- Coverage Endpoints ---

    @GetMapping("/coverage")
    public ResponseEntity<CoverageResponse> getCoverage() {
        List<CoverageHit> hits = coverageRegistry.snapshot().stream()
                .map(s -> new CoverageHit(s.method(), s.pattern(), s.ok(), s.clientError(), s.serverError()))
                .toList();
        return ResponseEntity.ok(new CoverageResponse(hits));
    }

    @PostMapping("/coverage/reset")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetCoverage() {
        coverageRegistry.reset();
    }
}
