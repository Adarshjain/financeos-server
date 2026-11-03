package com.financeos.api.accountdetect;

import com.financeos.api.accountdetect.dto.AccountSuggestionsResponse;
import com.financeos.api.accountdetect.dto.BelongsRequest;
import com.financeos.api.accountdetect.dto.IsThisRequest;
import com.financeos.core.security.UserContext;
import com.financeos.gmail.accountdetect.suggest.AccountSuggestionQuery;
import com.financeos.gmail.accountdetect.suggest.AccountSuggestionService;
import com.financeos.gmail.accountdetect.suggest.SuggestionBelongsService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/** Accounts and cards found in the user's mail: list, "not mine" (and undo), "Is this your …", "belongs to" and "not part". */
@RestController
@RequestMapping("/api/v1/account-suggestions")
public class AccountSuggestionController {

    private final AccountSuggestionQuery query;
    private final AccountSuggestionService service;
    private final SuggestionBelongsService belongsService;

    public AccountSuggestionController(AccountSuggestionQuery query, AccountSuggestionService service,
                                       SuggestionBelongsService belongsService) {
        this.query = query;
        this.service = service;
        this.belongsService = belongsService;
    }

    private static UUID requireCurrentUserId() {
        UUID userId = UserContext.getCurrentUserId();
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User is not authenticated");
        }
        return userId;
    }

    @GetMapping
    public ResponseEntity<AccountSuggestionsResponse> list() {
        return ResponseEntity.ok(query.list(requireCurrentUserId()));
    }

    @PostMapping("/{id}/not-mine")
    public ResponseEntity<Void> notMine(@PathVariable UUID id) {
        service.notMine(requireCurrentUserId(), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/undo")
    public ResponseEntity<Void> undo(@PathVariable UUID id) {
        service.undo(requireCurrentUserId(), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/is-this")
    public ResponseEntity<Void> isThis(@PathVariable UUID id, @Valid @RequestBody IsThisRequest request) {
        service.isThis(requireCurrentUserId(), id, request.accountId(), request.yes());
        return ResponseEntity.noContent().build();
    }

    /** "This belongs to that account": saves the number on an account the user has (older number, add-on, card, debit card, alias). */
    @PostMapping("/{id}/belongs")
    public ResponseEntity<Void> belongs(@PathVariable UUID id, @Valid @RequestBody BelongsRequest request) {
        belongsService.belongs(requireCurrentUserId(), id, request);
        return ResponseEntity.noContent().build();
    }

    /** "Not part of that account": refuses the "Older number on X?" link for good. */
    @PostMapping("/{id}/not-part")
    public ResponseEntity<Void> notPart(@PathVariable UUID id) {
        service.notPart(requireCurrentUserId(), id);
        return ResponseEntity.noContent().build();
    }
}
