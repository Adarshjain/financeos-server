package com.financeos.api.statement.dto;

/**
 * What deleting a statement would do, shown before the user confirms.
 *
 * @param transactionsToDelete  transactions this statement created and no other statement has a line for
 * @param transactionsKept      linked transactions that stay: ones it only matched (alerts, manual entries,
 *                              rows from other sources) or that another statement also covers
 * @param linkedToRecords       of those to delete, how many a loan, lending, dividend receipt or transfer
 *                              link points at — those records stay, unlinked
 * @param alertsToReopen        email alerts this statement reconciled that go back to review as unreconciled
 */
public record StatementDeletionPreviewResponse(
        int transactionsToDelete,
        int transactionsKept,
        int linkedToRecords,
        int alertsToReopen
) {
}
