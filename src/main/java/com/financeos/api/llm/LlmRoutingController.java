package com.financeos.api.llm;

import com.financeos.api.llm.dto.*;
import com.financeos.core.security.UserContext;
import com.financeos.domain.llm.LlmRoutingService;
import com.financeos.llm.LlmTaskGroup;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/llm")
public class LlmRoutingController {

    private final LlmRoutingService routingService;

    public LlmRoutingController(LlmRoutingService routingService) {
        this.routingService = routingService;
    }

    private UUID requireCurrentUserId() {
        UUID userId = UserContext.getCurrentUserId();
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User is not authenticated");
        }
        return userId;
    }

    private LlmTaskGroup parseGroup(String groupStr) {
        if (groupStr == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Task group cannot be null");
        }
        for (LlmTaskGroup g : LlmTaskGroup.values()) {
            if (g.getCode().equalsIgnoreCase(groupStr) || g.name().equalsIgnoreCase(groupStr)) {
                return g;
            }
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown task group: " + groupStr);
    }

    @GetMapping("/task-groups")
    public ResponseEntity<List<LlmTaskGroupDto>> getTaskGroups() {
        List<LlmTaskGroupDto> groups = Arrays.stream(LlmTaskGroup.values())
                .map(LlmTaskGroupDto::fromEnum)
                .toList();
        return ResponseEntity.ok(groups);
    }

    @GetMapping("/catalog")
    public ResponseEntity<List<ProviderCatalogDto>> getCatalog() {
        return ResponseEntity.ok(routingService.getCatalog());
    }

    /** The fixed menu a user orders. Options for providers with no active key come back unavailable. */
    @GetMapping("/routing-options")
    public ResponseEntity<List<RoutingOptionDto>> getRoutingOptions() {
        UUID userId = requireCurrentUserId();
        return ResponseEntity.ok(routingService.getRoutingOptions(userId));
    }

    @GetMapping("/routing")
    public ResponseEntity<LlmRoutingDto> getRouting() {
        UUID userId = requireCurrentUserId();
        return ResponseEntity.ok(routingService.getRouting(userId));
    }

    @PutMapping("/routing/{group}")
    public ResponseEntity<LlmRoutingGroupDto> updateRouting(
            @PathVariable String group,
            @Valid @RequestBody UpdateRoutingRequest request) {
        UUID userId = requireCurrentUserId();
        LlmTaskGroup taskGroup = parseGroup(group);
        try {
            LlmRoutingGroupDto updated = routingService.updateRouting(userId, taskGroup, request.entries());
            return ResponseEntity.ok(updated);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @PostMapping("/routing/{group}/reset")
    public ResponseEntity<LlmRoutingGroupDto> resetRouting(@PathVariable String group) {
        UUID userId = requireCurrentUserId();
        LlmTaskGroup taskGroup = parseGroup(group);
        LlmRoutingGroupDto reset = routingService.resetRouting(userId, taskGroup);
        return ResponseEntity.ok(reset);
    }

    @GetMapping("/health")
    public ResponseEntity<List<LlmBucketHealthDto>> getHealth() {
        UUID userId = requireCurrentUserId();
        return ResponseEntity.ok(routingService.getHealth(userId));
    }
}
