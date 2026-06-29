package com.financeos.api.gmail;

import com.financeos.api.gmail.dto.GmailSenderRequest;
import com.financeos.api.gmail.dto.GmailSenderResponse;
import com.financeos.api.gmail.dto.OAuthStartResponse;
import com.financeos.domain.user.AuthService;
import com.financeos.domain.user.User;
import com.financeos.gmail.domain.GmailConnection;
import com.financeos.gmail.ingest.GmailIngestionService;
import com.financeos.gmail.ingest.SenderAllowlistService;
import com.financeos.gmail.ingest.SyncSummary;
import com.financeos.gmail.oauth.GmailOAuthService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/gmail")
public class GmailController {

    private final GmailOAuthService oauthService;
    private final AuthService authService;
    private final GmailIngestionService gmailIngestionService;
    private final SenderAllowlistService senderAllowlistService;

    public GmailController(GmailOAuthService oauthService,
                           AuthService authService,
                           GmailIngestionService gmailIngestionService,
                           SenderAllowlistService senderAllowlistService) {
        this.oauthService = oauthService;
        this.authService = authService;
        this.gmailIngestionService = gmailIngestionService;
        this.senderAllowlistService = senderAllowlistService;
    }

    @GetMapping("/oauth/start")
    public ResponseEntity<OAuthStartResponse> startOAuth() {
        User currentUser = authService.getCurrentUser();
        String authUrl = oauthService.buildAuthorizationUrl(currentUser.getId());
        
        return ResponseEntity.ok(new OAuthStartResponse(authUrl));
    }

    @GetMapping("/oauth/callback")
    public ResponseEntity<Map<String, String>> handleCallback(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String error,
            @RequestParam(required = false) String state) {
        
        if (error != null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", error,
                    "message", "OAuth authorization failed"
            ));
        }

        if (code == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "missing_code",
                    "message", "Authorization code is required"
            ));
        }

        try {
            User currentUser = authService.getCurrentUser();
            GmailConnection connection = oauthService.handleCallback(currentUser.getId(), code);
            
            return ResponseEntity.ok(Map.of(
                    "status", "success",
                    "message", "Gmail connected successfully",
                    "email", connection.getEmail()
            ));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "callback_failed",
                    "message", e.getMessage()
            ));
        }
    }

    @PostMapping("/sync")
    public ResponseEntity<SyncSummary> syncEmails() {
        User currentUser = authService.getCurrentUser();
        GmailConnection connection = oauthService.getConnection(currentUser.getId());

        if (!connection.getIsConnected()) {
            return ResponseEntity.badRequest().build();
        }

        // Run full ingestion pipeline
        SyncSummary summary = gmailIngestionService.syncConnection(connection);

        return ResponseEntity.ok(summary);
    }

    @GetMapping("/senders")
    public ResponseEntity<List<GmailSenderResponse>> getSenders() {
        User currentUser = authService.getCurrentUser();
        List<GmailSenderResponse> response = senderAllowlistService.getSenders(currentUser.getId()).stream()
                .map(GmailSenderResponse::from)
                .toList();
        return ResponseEntity.ok(response);
    }

    @PostMapping("/senders")
    public ResponseEntity<GmailSenderResponse> createSender(@Valid @RequestBody GmailSenderRequest request) {
        User currentUser = authService.getCurrentUser();
        var sender = senderAllowlistService.createSender(currentUser.getId(), request);
        return ResponseEntity.ok(GmailSenderResponse.from(sender));
    }

    @PutMapping("/senders/{id}")
    public ResponseEntity<GmailSenderResponse> updateSender(
            @PathVariable UUID id,
            @Valid @RequestBody GmailSenderRequest request) {
        User currentUser = authService.getCurrentUser();
        var sender = senderAllowlistService.updateSender(currentUser.getId(), id, request);
        return ResponseEntity.ok(GmailSenderResponse.from(sender));
    }

    @DeleteMapping("/senders/{id}")
    public ResponseEntity<Void> deleteSender(@PathVariable UUID id) {
        User currentUser = authService.getCurrentUser();
        senderAllowlistService.deleteSender(currentUser.getId(), id);
        return ResponseEntity.noContent().build();
    }
}
