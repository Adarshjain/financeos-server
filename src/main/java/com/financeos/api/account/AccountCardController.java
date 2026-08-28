package com.financeos.api.account;

import com.financeos.api.account.dto.AccountCardResponse;
import com.financeos.api.account.dto.CloseCardRequest;
import com.financeos.api.account.dto.CreateAccountCardRequest;
import com.financeos.api.account.dto.UpdateAccountCardRequest;
import com.financeos.domain.account.card.AccountCardService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/accounts/{accountId}/cards")
public class AccountCardController {

    private final AccountCardService cardService;

    public AccountCardController(AccountCardService cardService) {
        this.cardService = cardService;
    }

    @GetMapping
    public ResponseEntity<List<AccountCardResponse>> getCards(@PathVariable UUID accountId) {
        return ResponseEntity.ok(cardService.getCardsForAccount(accountId));
    }

    @PostMapping
    public ResponseEntity<AccountCardResponse> createCard(
            @PathVariable UUID accountId,
            @Valid @RequestBody CreateAccountCardRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(cardService.createCard(accountId, request));
    }

    @PutMapping("/{cardId}")
    public ResponseEntity<AccountCardResponse> updateCard(
            @PathVariable UUID accountId,
            @PathVariable UUID cardId,
            @Valid @RequestBody UpdateAccountCardRequest request) {
        return ResponseEntity.ok(cardService.updateCard(accountId, cardId, request));
    }

    @PostMapping("/{cardId}/close")
    public ResponseEntity<AccountCardResponse> closeCard(
            @PathVariable UUID accountId,
            @PathVariable UUID cardId,
            @RequestBody(required = false) CloseCardRequest request) {
        return ResponseEntity.ok(cardService.closeCard(accountId, cardId, request));
    }

    @PostMapping("/{cardId}/reopen")
    public ResponseEntity<AccountCardResponse> reopenCard(
            @PathVariable UUID accountId,
            @PathVariable UUID cardId) {
        return ResponseEntity.ok(cardService.reopenCard(accountId, cardId));
    }

    @PostMapping("/{cardId}/primary")
    public ResponseEntity<AccountCardResponse> setPrimary(
            @PathVariable UUID accountId,
            @PathVariable UUID cardId) {
        return ResponseEntity.ok(cardService.setPrimary(accountId, cardId));
    }

    @DeleteMapping("/{cardId}")
    public ResponseEntity<Void> deleteCard(
            @PathVariable UUID accountId,
            @PathVariable UUID cardId) {
        cardService.deleteCard(accountId, cardId);
        return ResponseEntity.ok().build();
    }
}
