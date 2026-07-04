package com.financeos.api.transaction;

import com.financeos.api.transaction.dto.*;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/transactions")
public class TransactionController {

    private final TransactionService transactionService;

    public TransactionController(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    @PostMapping
    public ResponseEntity<TransactionResponse> createTransaction(
            @Valid @RequestBody CreateTransactionRequest request) {
        Transaction transaction = transactionService.createTransaction(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(TransactionResponse.from(transaction));
    }

    @GetMapping
    public ResponseEntity<Page<TransactionResponse>> getAllTransactions(
            @PageableDefault(size = 50, sort = { "date", "createdAt",
                    "id" }, direction = org.springframework.data.domain.Sort.Direction.DESC) Pageable pageable) {
        Page<Transaction> transactions = transactionService.getAllTransactions(pageable);
        Page<TransactionResponse> response = transactions.map(t -> TransactionResponse.from(t, t.getBalance()));
        return ResponseEntity.ok(response);
    }

    @PostMapping("/search")
    public ResponseEntity<Page<TransactionResponse>> searchTransactions(
            @Valid @RequestBody TransactionSearchRequest request,
            @PageableDefault(size = 50, sort = { "date", "createdAt",
                    "id" }, direction = org.springframework.data.domain.Sort.Direction.DESC) Pageable pageable) {
        Page<Transaction> transactions = transactionService.searchTransactions(request, pageable);
        Page<TransactionResponse> response = transactions.map(t -> TransactionResponse.from(t, t.getBalance()));
        return ResponseEntity.ok(response);
    }

    @PutMapping("/{id}")
    public ResponseEntity<TransactionResponse> updateTransaction(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateTransactionRequest request) {
        Transaction transaction = transactionService.updateTransaction(id, request);
        return ResponseEntity.ok(TransactionResponse.from(transaction));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteTransaction(@PathVariable UUID id) {
        transactionService.deleteTransaction(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/batch-review")
    public ResponseEntity<BatchReviewResponse> batchReview(
            @Valid @RequestBody BatchReviewRequest request) {
        int updated = transactionService.batchReview(request.transactionIds(), request.reviewType());
        return ResponseEntity.ok(new BatchReviewResponse(updated));
    }

    @PostMapping("/batch-delete")
    public ResponseEntity<BatchDeleteResponse> batchDelete(
            @Valid @RequestBody BatchDeleteRequest request) {
        int deleted = transactionService.batchDelete(request.transactionIds());
        return ResponseEntity.ok(new BatchDeleteResponse(deleted));
    }
}
