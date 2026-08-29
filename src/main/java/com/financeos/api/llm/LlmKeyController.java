package com.financeos.api.llm;

import com.financeos.api.llm.dto.CreateLlmKeyRequest;
import com.financeos.api.llm.dto.LlmKeyDto;
import com.financeos.api.llm.dto.TestKeyRequest;
import com.financeos.api.llm.dto.TestKeyResponse;
import com.financeos.api.llm.dto.UpdateLlmKeyPositionRequest;
import com.financeos.core.security.UserContext;
import com.financeos.domain.llm.LlmKeyService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/llm-keys")
public class LlmKeyController {

    private final LlmKeyService keyService;

    public LlmKeyController(LlmKeyService keyService) {
        this.keyService = keyService;
    }

    private UUID requireCurrentUserId() {
        UUID userId = UserContext.getCurrentUserId();
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User is not authenticated");
        }
        return userId;
    }

    @GetMapping
    public ResponseEntity<List<LlmKeyDto>> getKeys() {
        UUID userId = requireCurrentUserId();
        return ResponseEntity.ok(keyService.listKeys(userId));
    }

    @PostMapping
    public ResponseEntity<LlmKeyDto> createKey(@Valid @RequestBody CreateLlmKeyRequest request) {
        UUID userId = requireCurrentUserId();
        try {
            LlmKeyDto created = keyService.createKey(userId, request);
            return ResponseEntity.status(HttpStatus.CREATED).body(created);
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteKey(@PathVariable UUID id) {
        UUID userId = requireCurrentUserId();
        try {
            keyService.deleteKey(userId, id);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    @PatchMapping("/{id}/position")
    public ResponseEntity<List<LlmKeyDto>> updatePosition(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateLlmKeyPositionRequest request) {
        UUID userId = requireCurrentUserId();
        try {
            List<LlmKeyDto> updated = keyService.updatePosition(userId, id, request.position());
            return ResponseEntity.ok(updated);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @PostMapping("/{id}/test")
    public ResponseEntity<TestKeyResponse> testKey(
            @PathVariable UUID id,
            @RequestBody(required = false) TestKeyRequest request) {
        UUID userId = requireCurrentUserId();
        String model = request != null ? request.model() : null;
        try {
            TestKeyResponse response = keyService.testKey(userId, id, model);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }
}
