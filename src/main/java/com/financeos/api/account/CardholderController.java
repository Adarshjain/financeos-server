package com.financeos.api.account;

import com.financeos.api.account.dto.*;
import com.financeos.domain.account.card.CardholderService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/accounts/{accountId}/cardholders")
public class CardholderController {

    private final CardholderService cardholderService;

    public CardholderController(CardholderService cardholderService) {
        this.cardholderService = cardholderService;
    }

    @GetMapping
    public List<CardholderResponse> listByAccount(@PathVariable UUID accountId) {
        return cardholderService.listByAccount(accountId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CardholderResponse addAddon(
            @PathVariable UUID accountId,
            @Valid @RequestBody CreateCardholderRequest request) {
        return cardholderService.addAddon(accountId, request);
    }

    @PostMapping("/primary")
    @ResponseStatus(HttpStatus.CREATED)
    public CardholderResponse addPrimary(
            @PathVariable UUID accountId,
            @Valid @RequestBody CreateCardRequest request) {
        return cardholderService.addPrimaryWithCard(accountId, request);
    }

    @PutMapping("/{cardholderId}")
    public CardholderResponse updateCardholder(
            @PathVariable UUID accountId,
            @PathVariable UUID cardholderId,
            @Valid @RequestBody UpdateCardholderRequest request) {
        return cardholderService.updateCardholder(accountId, cardholderId, request);
    }

    @PostMapping("/{cardholderId}/close")
    public CardholderResponse closeCardholder(
            @PathVariable UUID accountId,
            @PathVariable UUID cardholderId,
            @RequestBody(required = false) CloseCardholderRequest request) {
        return cardholderService.closeCardholder(accountId, cardholderId, request != null ? request.closedOn() : null);
    }

    @PostMapping("/{cardholderId}/reopen")
    public CardholderResponse reopenCardholder(
            @PathVariable UUID accountId,
            @PathVariable UUID cardholderId) {
        return cardholderService.reopenCardholder(accountId, cardholderId);
    }

    @DeleteMapping("/{cardholderId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteCardholder(
            @PathVariable UUID accountId,
            @PathVariable UUID cardholderId) {
        cardholderService.deleteCardholder(accountId, cardholderId);
    }

    @PostMapping("/{cardholderId}/cards")
    @ResponseStatus(HttpStatus.CREATED)
    public CardholderResponse addCard(
            @PathVariable UUID accountId,
            @PathVariable UUID cardholderId,
            @Valid @RequestBody CreateCardRequest request) {
        return cardholderService.addCard(accountId, cardholderId, request);
    }

    @PostMapping("/{cardholderId}/cards/{cardId}/replace")
    public CardholderResponse replaceCard(
            @PathVariable UUID accountId,
            @PathVariable UUID cardholderId,
            @PathVariable UUID cardId,
            @Valid @RequestBody ReplaceCardRequest request) {
        return cardholderService.replaceCard(accountId, cardholderId, cardId, request);
    }

    @PostMapping("/{cardholderId}/cards/{cardId}/close")
    public CardholderResponse closeCard(
            @PathVariable UUID accountId,
            @PathVariable UUID cardholderId,
            @PathVariable UUID cardId,
            @RequestBody(required = false) CloseCardRequest request) {
        return cardholderService.closeCard(accountId, cardholderId, cardId, request != null ? request.closedOn() : null);
    }
}
