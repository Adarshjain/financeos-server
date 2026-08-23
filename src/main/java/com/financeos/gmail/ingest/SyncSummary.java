package com.financeos.gmail.ingest;

public record SyncSummary(
    int discovered,
    int processed,
    int created,
    int reconciled,
    int skipped,
    int parked,
    int failedRetryable,
    int failedPermanent,
    long backlogRemaining
) {
    public SyncSummary(int discovered, int processed, int created, int reconciled, int skipped, int parked, int failedRetryable, int failedPermanent) {
        this(discovered, processed, created, reconciled, skipped, parked, failedRetryable, failedPermanent, 0L);
    }
}
