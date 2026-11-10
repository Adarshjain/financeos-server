package com.financeos.gmail.reconcile;

import com.financeos.domain.transaction.Transaction;
import com.financeos.gmail.domain.GmailProcessedStatus;

import com.fasterxml.jackson.annotation.JsonIgnore;
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
    boolean emptyStatement,              // true for "no lines parsed, skipped as non-statement" outcome
    @JsonIgnore @Nullable StatementFileHandoff fileHandoff  // the statement's original file, for the caller to store after commit; never serialised
) {
    public ReconSummary(int created, int matched, int failed, List<Transaction> createdTransactions,
                        @Nullable GmailProcessedStatus failureOutcome, @Nullable String failureReason,
                        @Nullable String attachmentFilename, @Nullable String accountLast4, boolean emptyStatement) {
        this(created, matched, failed, createdTransactions, failureOutcome, failureReason, attachmentFilename, accountLast4,
                emptyStatement, null);
    }

    public ReconSummary withFileHandoff(StatementFileHandoff handoff) {
        return new ReconSummary(created, matched, failed, createdTransactions, failureOutcome, failureReason,
                attachmentFilename, accountLast4, emptyStatement, handoff);
    }

    public ReconSummary(int created, int matched, int failed) {
        this(created, matched, failed, List.of(), null, null, null, null, false, null);
    }

    public ReconSummary(int created, int matched, int failed, List<Transaction> createdTransactions) {
        this(created, matched, failed, createdTransactions, null, null, null, null, false, null);
    }
}
