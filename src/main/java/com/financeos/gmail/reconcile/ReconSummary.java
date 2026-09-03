package com.financeos.gmail.reconcile;

import com.financeos.domain.transaction.Transaction;
import com.financeos.gmail.domain.GmailProcessedStatus;

import org.springframework.lang.Nullable;

import java.util.List;

public record ReconSummary(
    int created,
    int matched,
    int failed,
    List<Transaction> createdTransactions,
    @Nullable GmailProcessedStatus failureOutcome,  // set when failed > 0
    @Nullable String failureReason,
    @Nullable String attachmentFilename,           // the chosen attachment, when known
    @Nullable String accountLast4,                 // parsed statement account number tail for ACCOUNT_UNRESOLVED
    boolean emptyStatement               // true for "no lines parsed, skipped as non-statement" outcome
) {
    public ReconSummary(int created, int matched, int failed) {
        this(created, matched, failed, List.of(), null, null, null, null, false);
    }

    public ReconSummary(int created, int matched, int failed, List<Transaction> createdTransactions) {
        this(created, matched, failed, createdTransactions, null, null, null, null, false);
    }
}
