package com.financeos.api.cardfee;

import com.financeos.api.cardfee.dto.CardFeeChargeRequest;
import com.financeos.api.cardfee.dto.CardFeeChargeResponse;
import com.financeos.api.cardfee.dto.FeeChargeCandidateResponse;
import com.financeos.domain.cardfee.CardFeeKind;
import com.financeos.domain.cardfee.CardFeeService;

import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/card-fee-charges")
public class CardFeeChargeController {

    private final CardFeeService cardFeeService;

    public CardFeeChargeController(CardFeeService cardFeeService) {
        this.cardFeeService = cardFeeService;
    }

    @PutMapping
    public ResponseEntity<CardFeeChargeResponse> upsertCharge(@Valid @RequestBody CardFeeChargeRequest request) {
        return ResponseEntity.ok(cardFeeService.upsertCharge(request));
    }

    @DeleteMapping
    public ResponseEntity<Void> deleteCharge(
            @RequestParam UUID accountId,
            @RequestParam CardFeeKind kind,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate feeYearStart) {
        cardFeeService.deleteCharge(accountId, kind, feeYearStart);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/candidates")
    public ResponseEntity<List<FeeChargeCandidateResponse>> getCandidates(
            @RequestParam UUID accountId,
            @RequestParam CardFeeKind kind,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate feeYearStart) {
        return ResponseEntity.ok(cardFeeService.getCandidates(accountId, kind, feeYearStart));
    }
}
