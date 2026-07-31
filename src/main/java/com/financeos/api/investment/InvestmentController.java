package com.financeos.api.investment;

import com.financeos.api.investment.dto.*;
import com.financeos.domain.instrument.price.PriceRefreshResult;
import com.financeos.domain.instrument.price.PriceRefreshService;
import com.financeos.domain.investment.InvestmentService;
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

    public InvestmentController(InvestmentService investmentService,
                                PriceRefreshService priceRefreshService) {
        this.investmentService = investmentService;
        this.priceRefreshService = priceRefreshService;
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
            @PageableDefault(size = 50, sort = "tradeDate") Pageable pageable) {
        return investmentService.getTransactions(brokerAccountId, instrumentId, holdingId, search, pageable);
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
    public PriceRefreshResult refreshPrices(@RequestParam(required = false) UUID instrumentId) {
        return priceRefreshService.refresh(Optional.ofNullable(instrumentId));
    }
}
