package com.financeos.api.gmail;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * SKELETON CONTROLLER - Gmail integration endpoints.
 * TODO: Implement OAuth2 flow with Gmail API
 */
@RestController
@RequestMapping("/api/v1/gmail")
public class GmailController {

    @GetMapping("/oauth/start")
    public ResponseEntity<Map<String, String>> startOAuth() {
        // TODO: Generate OAuth2 authorization URL and return it
        return ResponseEntity.ok(Map.of(
                "message", "Gmail OAuth integration not implemented",
                "status", "skeleton"
        ));
    }

    @GetMapping("/oauth/callback")
    public ResponseEntity<Map<String, String>> handleCallback(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String error) {
        // TODO: Exchange code for access token and store it
        return ResponseEntity.ok(Map.of(
                "message", "Gmail OAuth callback not implemented",
                "status", "skeleton"
        ));
    }

    @PostMapping("/sync")
    public ResponseEntity<Map<String, Object>> syncEmails() {
        // TODO: Fetch emails from Gmail and parse transactions
        return ResponseEntity.ok(Map.of(
                "message", "Gmail sync not implemented",
                "status", "skeleton",
                "transactionsImported", 0
        ));
    }
}

