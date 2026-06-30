package com.financeos.api.gmail;

import com.financeos.api.gmail.dto.GmailConnectionResponse;
import com.financeos.api.gmail.dto.GmailSenderRequest;
import com.financeos.api.gmail.dto.GmailSenderResponse;
import com.financeos.api.gmail.dto.OAuthStartResponse;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.domain.user.AuthService;
import com.financeos.domain.user.User;
import com.financeos.gmail.domain.GmailConnection;
import com.financeos.gmail.domain.GmailConnectionRepository;
import com.financeos.gmail.history.SyncStateService;
import com.financeos.gmail.ingest.GmailIngestionService;
import com.financeos.gmail.ingest.SenderAllowlistService;
import com.financeos.gmail.ingest.SyncSummary;
import com.financeos.gmail.oauth.GmailOAuthService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/gmail")
public class GmailController {

    private static final Logger log = LoggerFactory.getLogger(GmailController.class);

    private final GmailOAuthService oauthService;
    private final AuthService authService;
    private final GmailIngestionService gmailIngestionService;
    private final SenderAllowlistService senderAllowlistService;
    private final GmailConnectionRepository connectionRepository;
    private final SyncStateService syncStateService;

    @Value("${app.ui-path:http://localhost:3001}")
    private String uiPath;

    public GmailController(GmailOAuthService oauthService,
                           AuthService authService,
                           GmailIngestionService gmailIngestionService,
                           SenderAllowlistService senderAllowlistService,
                           GmailConnectionRepository connectionRepository,
                           SyncStateService syncStateService) {
        this.oauthService = oauthService;
        this.authService = authService;
        this.gmailIngestionService = gmailIngestionService;
        this.senderAllowlistService = senderAllowlistService;
        this.connectionRepository = connectionRepository;
        this.syncStateService = syncStateService;
    }

    @GetMapping("/oauth/start")
    public ResponseEntity<OAuthStartResponse> startOAuth() {
        User currentUser = authService.getCurrentUser();
        String authUrl = oauthService.buildAuthorizationUrl(currentUser.getId());
        
        return ResponseEntity.ok(new OAuthStartResponse(authUrl));
    }

    @GetMapping("/oauth/callback")
    public ResponseEntity<Void> handleCallback(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String error,
            @RequestParam(required = false) String state) {
        
        if (error != null) {
            String redirectUrl = uiPath + "/settings/gmail?gmail=error&message=" + URLEncoder.encode(error, StandardCharsets.UTF_8);
            return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(redirectUrl)).build();
        }

        if (code == null) {
            String redirectUrl = uiPath + "/settings/gmail?gmail=error&message=" + URLEncoder.encode("missing_code", StandardCharsets.UTF_8);
            return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(redirectUrl)).build();
        }

        try {
            User currentUser = authService.getCurrentUser();
            GmailConnection connection = oauthService.handleCallback(currentUser.getId(), code);
            
            String redirectUrl = uiPath + "/settings/gmail?gmail=success&email=" + URLEncoder.encode(connection.getEmail(), StandardCharsets.UTF_8);
            return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(redirectUrl)).build();
        } catch (Exception e) {
            String redirectUrl = uiPath + "/settings/gmail?gmail=error&message=" + URLEncoder.encode(e.getMessage() != null ? e.getMessage() : "callback_failed", StandardCharsets.UTF_8);
            return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(redirectUrl)).build();
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

    @GetMapping("/connections")
    public ResponseEntity<List<GmailConnectionResponse>> getConnections() {
        User currentUser = authService.getCurrentUser();
        List<GmailConnection> connections = connectionRepository.findByUserId(currentUser.getId());
        List<GmailConnectionResponse> responses = connections.stream()
                .map(connection -> {
                    var syncState = syncStateService.getSyncState(connection.getId());
                    Instant lastSyncedAt = syncState != null ? syncState.lastSyncedAt() : null;
                    return GmailConnectionResponse.from(connection, lastSyncedAt);
                })
                .toList();
        return ResponseEntity.ok(responses);
    }

    @DeleteMapping("/connections/{id}")
    public ResponseEntity<Void> disconnectConnection(@PathVariable UUID id) {
        User currentUser = authService.getCurrentUser();
        GmailConnection connection = connectionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Gmail connection", id));

        // Ownership check
        if (!connection.getUser().getId().equals(currentUser.getId())) {
            log.error("Security Breach Attempt: User {} tried to disconnect Gmail connection {} owned by User {}",
                    currentUser.getId(), id, connection.getUser().getId());
            throw new ValidationException("You do not have permission to access this connection.");
        }

        connection.setIsConnected(false);
        connectionRepository.save(connection);
        syncStateService.deleteSyncState(id);

        return ResponseEntity.noContent().build();
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
