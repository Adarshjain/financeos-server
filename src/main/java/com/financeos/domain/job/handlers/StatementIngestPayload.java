package com.financeos.domain.job.handlers;

import java.util.UUID;

/**
 * {@code accountId} null means "find the account from the file" (auto-detect). {@code encryptedPassword} is a password
 * the user typed for these files, encrypted with the application key; never stored in the clear.
 */
public record StatementIngestPayload(UUID accountId, String encryptedPassword) {
    public StatementIngestPayload(UUID accountId) {
        this(accountId, null);
    }
}
