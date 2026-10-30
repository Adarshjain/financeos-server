package com.financeos.api.linksuggestion;

import com.financeos.api.linksuggestion.dto.AcceptLinkSuggestionRequest;
import com.financeos.api.linksuggestion.dto.LinkSuggestionResponse;
import com.financeos.api.transactionlink.dto.TransactionLinkResponse;
import com.financeos.core.security.UserContext;
import com.financeos.domain.transaction.autolink.LinkSuggestionService;
import jakarta.validation.Valid;
import java.util.List;
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

/** Suggested links (transfers, card payments, refunds) the user can accept or dismiss. */
@RestController
@RequestMapping("/api/v1/link-suggestions")
public class LinkSuggestionController {

    private final LinkSuggestionService service;

    public LinkSuggestionController(LinkSuggestionService service) {
        this.service = service;
    }

    private static UUID requireCurrentUserId() {
        UUID userId = UserContext.getCurrentUserId();
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User is not authenticated");
        }
        return userId;
    }

    @GetMapping
    public ResponseEntity<List<LinkSuggestionResponse>> list() {
        return ResponseEntity.ok(service.list(requireCurrentUserId()));
    }

    @PostMapping("/{id}/accept")
    public ResponseEntity<TransactionLinkResponse> accept(@PathVariable UUID id,
                                                          @Valid @RequestBody AcceptLinkSuggestionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.accept(requireCurrentUserId(), id, request.candidateId()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> dismiss(@PathVariable UUID id) {
        service.dismiss(requireCurrentUserId(), id);
        return ResponseEntity.noContent().build();
    }
}
