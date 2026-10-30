package com.financeos.domain.transaction.autolink;

import java.util.UUID;

/** Published once per batch of newly written transactions (statement import, Gmail batch, manual entry). */
public record TransactionsIngestedEvent(UUID userId) {
}
