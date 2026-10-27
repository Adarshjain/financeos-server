package com.financeos.api.investment;

import org.springdoc.core.annotations.ParameterObject;

import com.financeos.api.investment.dto.*;
import com.financeos.domain.instrument.price.PriceRefreshResult;
import com.financeos.domain.instrument.price.PriceRefreshService;
import com.financeos.domain.investment.HoldingsService;
import com.financeos.domain.investment.InvestmentService;
import com.financeos.domain.investment.InvestmentTransactionType;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/investments")
public class InvestmentController {

    private final InvestmentService investmentService;
    private final PriceRefreshService priceRefreshService;
    private final HoldingsService holdingsService;
    private final com.financeos.domain.job.JobService jobService;

    public InvestmentController(InvestmentService investmentService,
                                PriceRefreshService priceRefreshService,
                                HoldingsService holdingsService,
                                com.financeos.domain.job.JobService jobService) {
        this.investmentService = investmentService;
        this.priceRefreshService = priceRefreshService;
        this.holdingsService = holdingsService;
        this.jobService = jobService;
    }

    @PostMapping("/transactions")
    @ResponseStatus(HttpStatus.CREATED)
    public InvestmentTransactionResponse createTransaction(
            @Valid @RequestBody CreateInvestmentTransactionRequest request) {
        return investmentService.createTransaction(request);
    }

    @PutMapping("/transactions/{id}")
    public InvestmentTransactionResponse updateTransaction(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateInvestmentTransactionRequest request) {
        return investmentService.updateTransaction(id, request);
    }

    @DeleteMapping("/transactions/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteTransaction(@PathVariable UUID id) {
        investmentService.deleteTransaction(id);
    }

    @GetMapping("/transactions")
    public Page<InvestmentTransactionResponse> getTransactions(
            @RequestParam(required = false) UUID brokerAccountId,
            @RequestParam(required = false) UUID instrumentId,
            @RequestParam(required = false) UUID holdingId,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) InvestmentTransactionType type,
            @RequestParam(required = false) Integer fy,
            @ParameterObject @PageableDefault(size = 50, sort = "tradeDate") Pageable pageable) {
        return investmentService.getTransactions(brokerAccountId, instrumentId, holdingId, search, type, fy, pageable);
    }

    /** One row per instrument across broker accounts; sort = name (default) | return_pct | return_abs; dir = asc | desc (default: name A-Z, returns best first).
     *  Always grouped by asset type first (stock, mutual_fund, etf), then sorted, then paged. */
    @GetMapping("/holdings")
    public HoldingsResponse getHoldings(
            @RequestParam(required = false) UUID brokerAccountId,
            @RequestParam(required = false) String assetType,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String dir,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return holdingsService.list(brokerAccountId, assetType, search, sort, dir, page, size);
    }

    @GetMapping("/holdings/{instrumentId}")
    public HoldingDetailResponse getHolding(@PathVariable UUID instrumentId) {
        return holdingsService.detail(instrumentId);
    }

    @GetMapping("/holdings/{instrumentId}/sell-estimate")
    public SellEstimateResponse getSellEstimate(@PathVariable UUID instrumentId,
                                                @RequestParam(required = false) java.math.BigDecimal quantity) {
        return holdingsService.sellEstimate(instrumentId, quantity);
    }

    @GetMapping("/positions")
    public PositionsResponse getPositions() {
        return investmentService.getPositions();
    }

    @GetMapping("/summary")
    public SummaryResponse getSummary() {
        return investmentService.getSummary();
    }

    @PostMapping("/prices/refresh")
    public org.springframework.http.ResponseEntity<com.financeos.api.job.dto.EnqueueResponse> refreshPrices(@RequestParam(required = false) UUID instrumentId) {
        UUID currentUserId = com.financeos.core.security.UserContext.getCurrentUserId();
        com.financeos.domain.job.Job job = jobService.enqueue(
                currentUserId,
                com.financeos.domain.job.JobType.PRICE_REFRESH,
                com.financeos.domain.job.JobTrigger.USER,
                new com.financeos.domain.job.handlers.PriceRefreshPayload(instrumentId),
                null,
                instrumentId != null ? "manual-" + instrumentId : "manual"
        );
        return org.springframework.http.ResponseEntity.accepted().body(new com.financeos.api.job.dto.EnqueueResponse(job.getId()));
    }
}
