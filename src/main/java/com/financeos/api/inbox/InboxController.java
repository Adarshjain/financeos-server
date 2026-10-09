package com.financeos.api.inbox;

import com.financeos.api.inbox.dto.InboxResponse;
import com.financeos.api.inbox.dto.InboxSnoozeRequest;
import com.financeos.api.inbox.dto.InboxSummaryResponse;
import com.financeos.core.security.UserContext;
import com.financeos.domain.inbox.InboxService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The inbox: a read of everything needing attention, plus snooze / dismiss / undo on a row key.
 * Keys contain ':' (e.g. {@code emi:<loanId>:<seq>}) but never '/' or '.', so a plain {@code {key}}
 * segment matches them; the client URL-encodes them and Spring decodes before matching. Every write returns the fresh inbox
 * so the client never needs a second round trip.
 */
@RestController
@RequestMapping("/api/v1/inbox")
public class InboxController {

    private final InboxService inboxService;

    public InboxController(InboxService inboxService) {
        this.inboxService = inboxService;
    }

    private UUID requireCurrentUserId() {
        UUID userId = UserContext.getCurrentUserId();
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User is not authenticated");
        }
        return userId;
    }

    @GetMapping
    public ResponseEntity<InboxResponse> list() {
        return ResponseEntity.ok(inboxService.list(requireCurrentUserId()));
    }

    @GetMapping("/summary")
    public ResponseEntity<InboxSummaryResponse> summary() {
        return ResponseEntity.ok(inboxService.summaryOnly(requireCurrentUserId()));
    }

    @PostMapping("/{key}/snooze")
    public ResponseEntity<InboxResponse> snooze(@PathVariable("key") String key, @Valid @RequestBody InboxSnoozeRequest request) {
        UUID userId = requireCurrentUserId();
        inboxService.snooze(userId, key, request.until());
        return ResponseEntity.ok(inboxService.list(userId));
    }

    @PostMapping("/{key}/dismiss")
    public ResponseEntity<InboxResponse> dismiss(@PathVariable("key") String key) {
        UUID userId = requireCurrentUserId();
        inboxService.dismiss(userId, key);
        return ResponseEntity.ok(inboxService.list(userId));
    }

    @DeleteMapping("/{key}/state")
    public ResponseEntity<InboxResponse> clearState(@PathVariable("key") String key) {
        UUID userId = requireCurrentUserId();
        inboxService.clearState(userId, key);
        return ResponseEntity.ok(inboxService.list(userId));
    }
}
