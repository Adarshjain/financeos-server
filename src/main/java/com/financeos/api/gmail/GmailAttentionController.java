package com.financeos.api.gmail;

import com.financeos.api.gmail.dto.AttentionBulkRequest;
import com.financeos.api.gmail.dto.AttentionBulkResponse;
import com.financeos.api.gmail.dto.AttentionReasonCount;
import com.financeos.domain.user.AuthService;
import com.financeos.gmail.attention.GmailAttentionService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Per-reason summary and bulk actions over the current user's Gmail "needs attention" items. */
@RestController
public class GmailAttentionController {

    private final AuthService authService;
    private final GmailAttentionService service;

    public GmailAttentionController(AuthService authService, GmailAttentionService service) {
        this.authService = authService;
        this.service = service;
    }

    @GetMapping("/api/v1/gmail/attention/summary")
    public ResponseEntity<List<AttentionReasonCount>> summary(
            @RequestParam(defaultValue = "false") boolean includeRetryable,
            @RequestParam(defaultValue = "false") boolean ungroupedOnly) {
        return ResponseEntity.ok(service.summary(authService.getCurrentUser().getId(), includeRetryable, ungroupedOnly));
    }

    @PostMapping("/api/v1/gmail/attention/retry-all")
    public ResponseEntity<AttentionBulkResponse> retryAll(@Valid @RequestBody AttentionBulkRequest request) {
        return ResponseEntity.ok(new AttentionBulkResponse(
                service.retryAll(authService.getCurrentUser().getId(), request.reason(), request.ungroupedOnly())));
    }

    @PostMapping("/api/v1/gmail/attention/archive-all")
    public ResponseEntity<AttentionBulkResponse> archiveAll(@Valid @RequestBody AttentionBulkRequest request) {
        return ResponseEntity.ok(new AttentionBulkResponse(
                service.archiveAll(authService.getCurrentUser().getId(), request.reason(), request.ungroupedOnly())));
    }
}
