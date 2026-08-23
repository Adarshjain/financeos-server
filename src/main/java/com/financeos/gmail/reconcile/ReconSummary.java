package com.financeos.gmail.reconcile;

import com.financeos.domain.transaction.Transaction;
import com.financeos.gmail.ingest.SyncSummary;

import java.util.List;

public record ReconSummary(
    int created,
    int matched,
    int failed,
    List<Transaction> createdTransactions,
    SyncSummary.Outcome failureOutcome,  // nullable — set when failed > 0
    String failureReason,                // nullable
    String attachmentFilename,           // nullable — the chosen attachment, when known
    String accountLast4,                 // nullable — parsed statement account number tail for ACCOUNT_UNRESOLVED
    boolean emptyStatement               // true for "no lines parsed, skipped as non-statement" outcome
) {
    public ReconSummary(int created, int matched, int failed) {
        this(created, matched, failed, List.of(), null, null, null, null, false);
    }

    public ReconSummary(int created, int matched, int failed, List<Transaction> createdTransactions) {
        this(created, matched, failed, createdTransactions, null, null, null, null, false);
    }
}

