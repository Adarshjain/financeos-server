package com.financeos.gmail.ingest;

import java.util.List;

public record SyncSummary(
    int fetched,
    int created,
    int skipped,          // total skipped (benign included)
    int failed,
    int reconciled,
    int alreadyProcessed, // subset of skipped — message was in the processed ledger
    int nonTransaction,   // subset of skipped — Gemini classified as non-transaction OR empty statement
    List<MessageOutcome> attention,  // one row per failed / actionable message, capped at 50
    int attentionTruncated           // count of attention items dropped by the cap
) {
    public SyncSummary(int fetched, int created, int skipped, int failed, int reconciled) {
        this(fetched, created, skipped, failed, reconciled, 0, 0, List.of(), 0);
    }

    public enum Outcome {
        EXTRACTION_FAILED,    // Gemini extraction failed on an alert email
        ACCOUNT_UNRESOLVED,   // no (or >1) account matches the last4 / account number
        DECRYPT_FAILED,       // encrypted statement, no stored password opened it
        PARSE_FAILED,         // statement parser returned failure
        NO_ATTACHMENT,        // routed as statement but no usable attachment
        ERROR                 // unexpected exception while processing the message
    }

    public record MessageOutcome(
        String gmailMessageId,
        String from,
        String subject,
        String receivedAt,         // ISO-8601 string
        String attachmentFilename, // nullable — set for statement-path outcomes
        Outcome outcome,
        String reason,             // human-readable detail, truncated to 500 chars
        String accountLast4        // nullable — set for ACCOUNT_UNRESOLVED
    ) {}
}

