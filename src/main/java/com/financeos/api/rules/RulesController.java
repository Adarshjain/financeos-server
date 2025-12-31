package com.financeos.api.rules;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * SKELETON CONTROLLER - Transaction categorization rules.
 * TODO: Implement rule-based transaction categorization
 */
@RestController
@RequestMapping("/api/v1/rules")
public class RulesController {

    @PostMapping
    public ResponseEntity<Map<String, Object>> createRule(@RequestBody Map<String, Object> request) {
        // TODO: Create categorization rule
        // Rule structure: { pattern, category, subcategory, spentFor }
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "message", "Rules creation not implemented",
                "status", "skeleton"
        ));
    }

    @PostMapping("/apply")
    public ResponseEntity<Map<String, Object>> applyRules() {
        // TODO: Apply all rules to uncategorized transactions
        return ResponseEntity.ok(Map.of(
                "message", "Rules application not implemented",
                "status", "skeleton",
                "transactionsUpdated", 0
        ));
    }
}

