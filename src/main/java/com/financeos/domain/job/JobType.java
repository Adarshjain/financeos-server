package com.financeos.domain.job;

public enum JobType {
    STATEMENT_INGEST,
    GMAIL_SYNC,
    PRICE_REFRESH,
    INVESTMENT_IMPORT_COMMIT,
    BROKER_RECONCILE_COMMIT,
    RULE_APPLY
}
