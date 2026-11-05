package com.financeos.domain.transaction.autolink;

import com.financeos.domain.transaction.Transaction;
import java.util.Collection;
import java.util.UUID;

/**
 * Published once per batch of newly written transactions (statement import, Gmail batch, manual entry).
 * {@code scope} is the span of their dates; {@link LinkScope#ALL} when unknown.
 */
public record TransactionsIngestedEvent(UUID userId, LinkScope scope) {

    public TransactionsIngestedEvent {
        scope = scope == null ? LinkScope.ALL : scope;
    }

    /** Full-scope event. */
    public TransactionsIngestedEvent(UUID userId) {
        this(userId, LinkScope.ALL);
    }

    /** Event spanning the dates of the written transactions. */
    public static TransactionsIngestedEvent of(UUID userId, Collection<Transaction> written) {
        return new TransactionsIngestedEvent(userId, LinkScope.spanOf(written, Transaction::getDate));
    }
}
