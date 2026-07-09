package com.financeos.gmail.reconcile;

import com.financeos.domain.transaction.Transaction;

import java.util.List;

public record ReconSummary(
    int created,
    int matched,
    int failed,
    List<Transaction> createdTransactions
) {
    public ReconSummary(int created, int matched, int failed) {
        this(created, matched, failed, List.of());
    }
}
