package com.financeos.api.statement.dto;

/**
 * Outcome of deleting a statement.
 *
 * @param duplicateFlagsCleared surviving transactions whose only suspected duplicate was deleted
 */
public record StatementDeletionResponse(
        int transactionsDeleted,
        int transactionsKept,
        int alertsReopened,
        int duplicateFlagsCleared
) {
}
