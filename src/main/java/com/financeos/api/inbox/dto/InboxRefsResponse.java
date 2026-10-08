package com.financeos.api.inbox.dto;

import java.util.UUID;
import org.springframework.lang.Nullable;

/** The entities an inbox row is about, for clients that deep-link or cross-reference. */
public record InboxRefsResponse(
        @Nullable UUID statementId,
        @Nullable UUID accountId,
        @Nullable UUID loanId,
        @Nullable UUID counterpartyId,
        @Nullable UUID connectionId,
        @Nullable UUID jobId
) {
    public static final InboxRefsResponse NONE = new InboxRefsResponse(null, null, null, null, null, null);

    public static InboxRefsResponse ofStatement(UUID statementId, UUID accountId) {
        return new InboxRefsResponse(statementId, accountId, null, null, null, null);
    }

    public static InboxRefsResponse ofAccount(UUID accountId) {
        return new InboxRefsResponse(null, accountId, null, null, null, null);
    }

    public static InboxRefsResponse ofLoan(UUID loanId) {
        return new InboxRefsResponse(null, null, loanId, null, null, null);
    }

    public static InboxRefsResponse ofCounterparty(UUID counterpartyId) {
        return new InboxRefsResponse(null, null, null, counterpartyId, null, null);
    }

    public static InboxRefsResponse ofConnection(UUID connectionId) {
        return new InboxRefsResponse(null, null, null, null, connectionId, null);
    }

    public static InboxRefsResponse ofJob(UUID jobId) {
        return new InboxRefsResponse(null, null, null, null, null, jobId);
    }
}
