package com.financeos.api.investment;

import com.financeos.api.investment.dto.CreateInvestmentTransactionRequest;
import com.financeos.api.investment.dto.InvestmentPositionResponse;
import com.financeos.api.investment.dto.InvestmentTransactionResponse;
import com.financeos.domain.investment.InvestmentService;
import com.financeos.domain.investment.InvestmentTransaction;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/investments")
public class InvestmentController {

    private final InvestmentService investmentService;

    public InvestmentController(InvestmentService investmentService) {
        this.investmentService = investmentService;
    }

    @PostMapping("/transactions")
    public ResponseEntity<InvestmentTransactionResponse> createTransaction(
            @Valid @RequestBody CreateInvestmentTransactionRequest request) {
        InvestmentTransaction transaction = investmentService.createTransaction(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(InvestmentTransactionResponse.from(transaction));
    }

    @GetMapping("/transactions")
    public ResponseEntity<Page<InvestmentTransactionResponse>> getAllTransactions(
            @PageableDefault(size = 50, sort = "date") Pageable pageable) {
        Page<InvestmentTransaction> transactions = investmentService.getAllTransactions(pageable);
        Page<InvestmentTransactionResponse> response = transactions.map(InvestmentTransactionResponse::from);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/position")
    public ResponseEntity<InvestmentPositionResponse> getPositions() {
        InvestmentPositionResponse positions = investmentService.getPositions();
        return ResponseEntity.ok(positions);
    }
}

