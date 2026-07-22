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
    private final com.financeos.domain.transaction.link.TransactionLinkService transactionLinkService;

    public TransactionController(TransactionService transactionService,
                                 com.financeos.domain.transaction.link.TransactionLinkService transactionLinkService) {
        this.transactionService = transactionService;
        this.transactionLinkService = transactionLinkService;
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
        java.util.List<UUID> ids = transactions.getContent().stream().map(Transaction::getId).toList();
        java.util.Map<UUID, java.util.List<com.financeos.api.transactionlink.dto.TransactionLinkSummary>> linkMap =
                transactionLinkService.linkSummariesFor(ids);
        Page<TransactionResponse> response = transactions.map(t -> TransactionResponse.from(t, t.getBalance(), linkMap));
        return ResponseEntity.ok(response);
    }

    @PostMapping("/search")
    public ResponseEntity<Page<TransactionResponse>> searchTransactions(
            @Valid @RequestBody TransactionSearchRequest request,
            @PageableDefault(size = 50, sort = { "date", "createdAt",
                    "id" }, direction = org.springframework.data.domain.Sort.Direction.DESC) Pageable pageable) {
        Page<Transaction> transactions = transactionService.searchTransactions(request, pageable);
        java.util.List<UUID> ids = transactions.getContent().stream().map(Transaction::getId).toList();
        java.util.Map<UUID, java.util.List<com.financeos.api.transactionlink.dto.TransactionLinkSummary>> linkMap =
                transactionLinkService.linkSummariesFor(ids);
        Page<TransactionResponse> response = transactions.map(t -> TransactionResponse.from(t, t.getBalance(), linkMap));
        return ResponseEntity.ok(response);
    }

    @PutMapping("/{id}")
    public ResponseEntity<TransactionResponse> updateTransaction(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateTransactionRequest request) {
        Transaction transaction = transactionService.updateTransaction(id, request);
        java.util.Map<UUID, java.util.List<com.financeos.api.transactionlink.dto.TransactionLinkSummary>> linkMap =
                transactionLinkService.linkSummariesFor(java.util.List.of(id));
        return ResponseEntity.ok(TransactionResponse.from(transaction, null, linkMap));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteTransaction(@PathVariable UUID id) {
        transactionService.deleteTransaction(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/batch-review")
    public ResponseEntity<BatchReviewResponse> batchReview(
            @Valid @RequestBody BatchReviewRequest request) {
        BatchReviewResponse response = transactionService.batchReview(
                request.transactionIds(), request.reviewType(), request.reviewReasons());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/batch-delete")
    public ResponseEntity<BatchDeleteResponse> batchDelete(
            @Valid @RequestBody BatchDeleteRequest request) {
        BatchDeleteResponse response = transactionService.batchDelete(request.transactionIds());
        return ResponseEntity.ok(response);
    }
}
