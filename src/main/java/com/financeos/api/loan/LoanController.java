package com.financeos.api.loan;

import com.financeos.api.loan.dto.*;
import com.financeos.domain.lending.LendingService;
import com.financeos.domain.loan.LoanService;
import com.financeos.domain.loan.LoanStatus;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/loans")
public class LoanController {

    private final LoanService loanService;
    private final LendingService lendingService;

    public LoanController(LoanService loanService, LendingService lendingService) {
        this.loanService = loanService;
        this.lendingService = lendingService;
    }

    @PostMapping
    public LoanResponse createLoan(@Valid @RequestBody CreateLoanRequest req) {
        return loanService.createLoan(req);
    }

    @GetMapping
    public Page<LoanResponse> getLoans(
            @RequestParam(required = false) LoanStatus status,
            @PageableDefault(size = 50, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return loanService.getLoans(status, pageable);
    }

    @GetMapping("/summary")
    public LoansSummaryResponse getLoansSummary() {
        Page<LoanResponse> activeLoans = loanService.getLoans(LoanStatus.active, Pageable.unpaged());
        BigDecimal totalOutstanding = activeLoans.getContent().stream()
                .map(LoanResponse::outstandingPrincipal)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        var lendingSummary = lendingService.getLendingTotals();

        return new LoansSummaryResponse(
                totalOutstanding,
                activeLoans.getTotalElements(),
                lendingSummary.lentOutstanding(),
                lendingSummary.borrowedOutstanding(),
                lendingSummary.netReceivable()
        );
    }

    @GetMapping("/{id}")
    public LoanDetailResponse getLoanDetail(@PathVariable UUID id) {
        return loanService.getLoanDetail(id);
    }

    @GetMapping("/{id}/schedule")
    public Map<String, List<InstallmentDto>> getSchedule(@PathVariable UUID id) {
        return Map.of("installments", loanService.getLoanSchedule(id));
    }

    @PutMapping("/{id}")
    public LoanResponse updateLoan(@PathVariable UUID id, @RequestBody UpdateLoanRequest req) {
        return loanService.updateLoan(id, req);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteLoan(@PathVariable UUID id) {
        loanService.deleteLoan(id);
    }

    @PostMapping("/{id}/close")
    @ResponseStatus(HttpStatus.OK)
    public void closeLoan(@PathVariable UUID id) {
        loanService.closeLoan(id);
    }

    @PostMapping("/{id}/reopen")
    @ResponseStatus(HttpStatus.OK)
    public void reopenLoan(@PathVariable UUID id) {
        loanService.reopenLoan(id);
    }

    @PostMapping("/{id}/events")
    public LoanEventResponse addEvent(@PathVariable UUID id, @Valid @RequestBody CreateLoanEventRequest req) {
        return loanService.addEvent(id, req);
    }

    @DeleteMapping("/{id}/events/{eventId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteEvent(@PathVariable UUID id, @PathVariable UUID eventId) {
        loanService.deleteEvent(id, eventId);
    }

    @PostMapping("/{id}/payments")
    public LoanPaymentResponse addPayment(@PathVariable UUID id, @Valid @RequestBody CreateLoanPaymentRequest req) {
        return loanService.addPayment(id, req);
    }

    @PostMapping("/{id}/payments/batch")
    public Map<String, Integer> addPaymentsBatch(@PathVariable UUID id, @Valid @RequestBody BatchLoanPaymentRequest req) {
        return loanService.addPaymentsBatch(id, req);
    }

    @DeleteMapping("/{id}/payments/{paymentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deletePayment(@PathVariable UUID id, @PathVariable UUID paymentId) {
        loanService.deletePayment(id, paymentId);
    }

    @PostMapping("/{id}/charges")
    public LoanChargeResponse addCharge(@PathVariable UUID id, @Valid @RequestBody CreateLoanChargeRequest req) {
        return loanService.addCharge(id, req);
    }

    @DeleteMapping("/{id}/charges/{chargeId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteCharge(@PathVariable UUID id, @PathVariable UUID chargeId) {
        loanService.deleteCharge(id, chargeId);
    }

    @GetMapping("/{id}/match-suggestions")
    public MatchSuggestionsResponse getMatchSuggestions(@PathVariable UUID id) {
        return loanService.getMatchSuggestions(id);
    }
}
