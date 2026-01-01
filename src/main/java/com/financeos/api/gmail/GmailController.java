package com.financeos.api.gmail;

import com.financeos.api.gmail.dto.GmailFetchRequestDto;
import com.financeos.api.gmail.dto.GmailFetchResultDto;
import com.financeos.api.gmail.dto.OAuthStartResponse;
import com.financeos.domain.user.AuthService;
import com.financeos.domain.user.User;
import com.financeos.gmail.domain.GmailConnection;
import com.financeos.gmail.engine.GmailEngine;
import com.financeos.gmail.internal.FetchMode;
import com.financeos.gmail.internal.GmailFetchRequest;
import com.financeos.gmail.oauth.GmailOAuthService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;

/**
 * Gmail Controller - Delegates to Gmail Engine.
 * NO business logic here - pure delegation.
 */
@RestController
@RequestMapping("/api/v1/gmail")
public class GmailController {

    private final GmailOAuthService oauthService;
    private final GmailEngine gmailEngine;
    private final AuthService authService;

    public GmailController(GmailOAuthService oauthService,
                           GmailEngine gmailEngine,
                           AuthService authService) {
        this.oauthService = oauthService;
        this.gmailEngine = gmailEngine;
        this.authService = authService;
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
    public ResponseEntity<GmailFetchResultDto> syncEmails(
            @Valid @RequestBody(required = false) GmailFetchRequestDto request) {
        
        User currentUser = authService.getCurrentUser();
        GmailConnection connection = oauthService.getConnection(currentUser.getId());

        if (!connection.getIsConnected()) {
            return ResponseEntity.badRequest().build();
        }

        // Build fetch request
        FetchMode mode = request != null && request.mode() != null 
                ? FetchMode.valueOf(request.mode()) 
                : FetchMode.MANUAL;
        Instant fromTime = request != null ? request.fromTime() : null;
        Integer maxMessages = request != null ? request.maxMessages() : 100;

        GmailFetchRequest fetchRequest = new GmailFetchRequest(mode, fromTime, maxMessages);

        // Delegate to engine
        var result = gmailEngine.fetch(connection, fetchRequest);

        // Convert to DTO
        GmailFetchResultDto dto = GmailFetchResultDto.from(result);

        return ResponseEntity.ok(dto);
    }
}
