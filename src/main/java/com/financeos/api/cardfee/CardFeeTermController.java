package com.financeos.api.cardfee;

import com.financeos.api.cardfee.dto.CardFeeTermRequest;
import com.financeos.api.cardfee.dto.CardFeeTermResponse;
import com.financeos.domain.cardfee.CardFeeService;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/card-fee-terms")
public class CardFeeTermController {

    private final CardFeeService cardFeeService;

    public CardFeeTermController(CardFeeService cardFeeService) {
        this.cardFeeService = cardFeeService;
    }

    @GetMapping
    public ResponseEntity<List<CardFeeTermResponse>> getTerms(@RequestParam UUID accountId) {
        return ResponseEntity.ok(cardFeeService.getTermsByAccountId(accountId));
    }

    @PostMapping
    public ResponseEntity<CardFeeTermResponse> createTerm(@Valid @RequestBody CardFeeTermRequest request) {
        CardFeeTermResponse created = cardFeeService.createTerm(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/{id}")
    public ResponseEntity<CardFeeTermResponse> updateTerm(
            @PathVariable UUID id,
            @Valid @RequestBody CardFeeTermRequest request) {
        return ResponseEntity.ok(cardFeeService.updateTerm(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteTerm(@PathVariable UUID id) {
        cardFeeService.deleteTerm(id);
        return ResponseEntity.noContent().build();
    }
}
