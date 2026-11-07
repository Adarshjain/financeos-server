package com.financeos.api.account;

import com.financeos.api.account.dto.CardSeenDatesResponse;
import com.financeos.domain.account.card.CardSeenDatesService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** When a card number was seen in mail and transactions, to prefill the dates of a past card. */
@RestController
@RequestMapping("/api/v1/accounts/{accountId}/cards")
public class CardSeenDatesController {

    private final CardSeenDatesService service;

    public CardSeenDatesController(CardSeenDatesService service) {
        this.service = service;
    }

    @GetMapping("/seen-dates")
    public ResponseEntity<CardSeenDatesResponse> seenDates(@PathVariable UUID accountId, @RequestParam String last4) {
        return ResponseEntity.ok(service.seenDates(accountId, last4));
    }
}
