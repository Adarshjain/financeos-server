package com.financeos.gmail.reconcile;

public record ReconSummary(
    int created,
    int matched,
    int failed
) {}
