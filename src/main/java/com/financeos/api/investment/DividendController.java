package com.financeos.api.investment;

import org.springdoc.core.annotations.ParameterObject;

import com.financeos.api.investment.dto.*;
import com.financeos.domain.investment.dividend.DividendReceiptService;
import com.financeos.domain.investment.dividend.DividendReceiptStatus;
import com.financeos.domain.investment.dividend.DividendService;
import com.financeos.domain.investment.dividend.DividendType;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.SortDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/investments/dividends")
public class DividendController {

    private final DividendService dividendService;
    private final DividendReceiptService dividendReceiptService;

    public DividendController(DividendService dividendService, DividendReceiptService dividendReceiptService) {
        this.dividendService = dividendService;
        this.dividendReceiptService = dividendReceiptService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DividendResponse createDividend(@Valid @RequestBody CreateDividendRequest request) {
        return dividendService.createDividend(request);
    }

    @PutMapping("/{id}")
    public DividendResponse updateDividend(@PathVariable UUID id, @Valid @RequestBody UpdateDividendRequest request) {
        return dividendService.updateDividend(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteDividend(@PathVariable UUID id) {
        dividendService.deleteDividend(id);
    }

    @GetMapping
    public Page<DividendResponse> getDividends(
            @RequestParam(required = false) UUID holdingId,
            @RequestParam(required = false) UUID brokerAccountId,
            @RequestParam(required = false) UUID instrumentId,
            @RequestParam(required = false) DividendType type,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) DividendReceiptStatus receipt,
            @ParameterObject @PageableDefault(size = 25)
            @SortDefault.SortDefaults({
                    @SortDefault(sort = "payDate", direction = Sort.Direction.DESC),
                    @SortDefault(sort = "createdAt", direction = Sort.Direction.DESC)
            }) Pageable pageable) {
        return dividendService.getDividends(holdingId, brokerAccountId, instrumentId, type, from, to, receipt, pageable);
    }

    @GetMapping("/summary")
    public DividendSummaryResponse getSummary(
            @RequestParam(required = false) UUID holdingId,
            @RequestParam(required = false) UUID brokerAccountId,
            @RequestParam(required = false) UUID instrumentId,
            @RequestParam(required = false) DividendType type) {
        return dividendService.getSummary(holdingId, brokerAccountId, instrumentId, type);
    }

    @GetMapping("/suggestions")
    public DividendSuggestionsResponse scanSuggestions(
            @RequestParam(required = false) UUID brokerAccountId) {
        return dividendService.scanSuggestions(brokerAccountId);
    }

    @PostMapping("/suggestions/accept")
    public AcceptSuggestionsResponse acceptSuggestions(
            @Valid @RequestBody AcceptSuggestionsRequest request) {
        return dividendService.acceptSuggestions(request);
    }

    // --- receipt reconciliation --------------------------------------------------------------------

    /** Attach or replace the bank credit this payout landed as. */
    @PutMapping("/{id}/transaction")
    public DividendResponse linkDividendTransaction(@PathVariable UUID id, @Valid @RequestBody LinkDividendTransactionRequest request) {
        return dividendReceiptService.linkTransaction(id, request.transactionId(), request.updateTds());
    }

    /** Detach the bank credit (idempotent). */
    @DeleteMapping("/{id}/transaction")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unlinkDividendTransaction(@PathVariable UUID id) {
        dividendReceiptService.unlinkTransaction(id);
    }

    /** Set or clear the manual receipt note (received_untracked / not_received). */
    @PutMapping("/{id}/receipt-status")
    public DividendResponse setDividendReceiptStatus(@PathVariable UUID id, @RequestBody SetDividendReceiptStatusRequest request) {
        return dividendReceiptService.setReceiptStatus(id, request.status());
    }

    /** Receipt status buckets (counts, expected net, received) honouring the list filters. */
    @GetMapping("/receipts/summary")
    public DividendReceiptSummaryResponse getDividendReceiptSummary(
            @RequestParam(required = false) UUID holdingId,
            @RequestParam(required = false) UUID brokerAccountId,
            @RequestParam(required = false) UUID instrumentId,
            @RequestParam(required = false) DividendType type) {
        return dividendReceiptService.getReceiptSummary(holdingId, brokerAccountId, instrumentId, type);
    }

    /** Unresolved dividends with their candidate bank credits, best first. */
    @GetMapping("/reconciliation")
    public DividendReconciliationResponse getDividendReconciliation(
            @RequestParam(required = false) UUID brokerAccountId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return dividendReceiptService.getReconciliation(brokerAccountId, from, to);
    }

    /** Link several dividend ↔ credit pairs at once; partial success is reported per item. */
    @PostMapping("/reconciliation/confirm")
    public ConfirmDividendMatchesResponse confirmDividendMatches(@Valid @RequestBody ConfirmDividendMatchesRequest request) {
        return dividendReceiptService.confirmMatches(request);
    }

    /** Bank credits that look like dividend payouts but are not recorded as dividends. */
    @GetMapping("/reconciliation/unrecorded")
    public UnrecordedDividendCreditsResponse getUnrecordedDividendCredits(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return dividendReceiptService.scanUnrecordedCredits(from, to);
    }
}
