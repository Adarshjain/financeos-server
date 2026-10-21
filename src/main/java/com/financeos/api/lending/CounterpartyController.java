package com.financeos.api.lending;

import org.springdoc.core.annotations.ParameterObject;

import com.financeos.api.lending.dto.CounterpartyDirection;
import com.financeos.api.lending.dto.CounterpartyLedgerResponse;
import com.financeos.api.lending.dto.CounterpartyMatchSuggestionsResponse;
import com.financeos.api.lending.dto.CounterpartyResponse;
import com.financeos.api.lending.dto.CounterpartySummaryResponse;
import com.financeos.api.lending.dto.CounterpartySuggestionResponse;
import com.financeos.api.lending.dto.CreateCounterpartyRequest;
import com.financeos.api.lending.dto.CreateSettlementRequest;
import com.financeos.api.lending.dto.LendingResponse;
import com.financeos.api.lending.dto.UnrecordedTransferResponse;
import com.financeos.api.lending.dto.LendingMatchSuggestionsResponse;
import com.financeos.api.lending.dto.UpdateCounterpartyRequest;
import com.financeos.domain.lending.LendingService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/counterparties")
public class CounterpartyController {

    private final LendingService lendingService;

    public CounterpartyController(LendingService lendingService) {
        this.lendingService = lendingService;
    }

    /**
     * The caller's people with their ledger totals, paged. {@code q} narrows by name;
     * {@code outstanding=true} keeps only people with a nonzero net position; {@code sort=net}
     * orders by absolute net position, largest first (then name), instead of the default name order.
     */
    @GetMapping
    @Operation(description = "People with ledger totals, paged. q narrows by name; outstanding=true keeps only "
            + "nonzero net positions; direction=OWED_TO_YOU|YOU_OWE|SETTLED filters by net sign; sort=net orders by absolute net position, largest first (then name).")
    public Page<CounterpartyResponse> getCounterparties(
            @RequestParam(required = false) String q,
            @RequestParam(required = false, defaultValue = "false") boolean outstanding,
            @RequestParam(required = false) CounterpartyDirection direction,
            @ParameterObject @PageableDefault(size = 50, sort = "name", direction = Sort.Direction.ASC) Pageable pageable) {
        return lendingService.getCounterparties(q, outstanding, direction, pageable);
    }

    @GetMapping("/summary")
    @Operation(operationId = "getCounterpartySummary", description = "Lendings tiles over all people: owedToYou, youOwe "
            + "(positive), and dueThisMonth / expectedBack buckets by expected return date up to the end of this month.")
    public CounterpartySummaryResponse getSummary() {
        return lendingService.getCounterpartySummary();
    }

    @GetMapping("/match-suggestions")
    @Operation(operationId = "getAllCounterpartyMatchSuggestions", description = "Match suggestions for every person with at least one candidate.")
    public List<CounterpartyMatchSuggestionsResponse> getAllMatchSuggestions() {
        return lendingService.getAllMatchSuggestions();
    }

    @GetMapping("/unrecorded-transfers")
    @Operation(operationId = "getUnrecordedTransfers", description = "Recent bank transfers (last 90 days) that name a person "
            + "but have no lending entry and would reduce that person's balance; direction is the settlement entry's direction. "
            + "Newest first, at most 50.")
    public List<UnrecordedTransferResponse> getUnrecordedTransfers() {
        return lendingService.getUnrecordedTransfers();
    }

    @PostMapping("/{id}/settlements")
    @Operation(operationId = "recordCounterpartySettlement", description = "Records a settlement entry from an unrecorded bank "
            + "transfer and links the transaction. 404 foreign/missing person or transaction; 409 transaction already referenced; "
            + "400 when it would not reduce the balance or exceeds it by more than the match tolerance.")
    public LendingResponse recordSettlement(@PathVariable UUID id, @Valid @RequestBody CreateSettlementRequest req) {
        return lendingService.recordSettlement(id, req.transactionId());
    }

    @GetMapping("/{id}")
    @Operation(operationId = "getCounterparty")
    public CounterpartyResponse getCounterparty(@PathVariable UUID id) {
        return lendingService.getCounterparty(id);
    }

    @GetMapping("/{id}/ledger")
    @Operation(operationId = "getCounterpartyLedger", description = "The person's entries newest first with a running balance, plus totals.")
    public CounterpartyLedgerResponse getLedger(@PathVariable UUID id, @ParameterObject @PageableDefault(size = 50) Pageable pageable) {
        return lendingService.getLedger(id, pageable);
    }

    @GetMapping("/suggest")
    @Operation(operationId = "suggestCounterparty")
    public CounterpartySuggestionResponse suggestCounterparty(@RequestParam String text) {
        return lendingService.suggestCounterparty(text);
    }

    @PostMapping
    public CounterpartyResponse createCounterparty(@Valid @RequestBody CreateCounterpartyRequest req) {
        return lendingService.createCounterparty(req);
    }

    @PutMapping("/{id}")
    public CounterpartyResponse updateCounterparty(@PathVariable UUID id, @RequestBody UpdateCounterpartyRequest req) {
        return lendingService.updateCounterparty(id, req);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteCounterparty(@PathVariable UUID id) {
        lendingService.deleteCounterparty(id);
    }

    @GetMapping("/{id}/match-suggestions")
    @Operation(operationId = "getCounterpartyMatchSuggestions")
    public LendingMatchSuggestionsResponse getMatchSuggestions(@PathVariable UUID id) {
        return lendingService.getMatchSuggestions(id);
    }
}
