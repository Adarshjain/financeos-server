package com.financeos.gmail.ingest;

public record SyncSummary(
    int fetched,
    int created,
    int skipped,
    int failed,
    int reconciled
) {}
