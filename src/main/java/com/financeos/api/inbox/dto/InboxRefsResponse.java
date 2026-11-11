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
        @Nullable UUID jobId,
        @Nullable UUID epfEntryId,
        @Nullable UUID transactionId,
        @Nullable UUID npsPendingId,
        @Nullable UUID npsEntryId,
        @Nullable UUID suggestionId,
        @Nullable UUID instrumentId
) {
    public static final InboxRefsResponse NONE = new InboxRefsResponse(null, null, null, null, null, null, null, null, null, null, null, null);

    public static InboxRefsResponse ofStatement(UUID statementId, UUID accountId) {
        return new InboxRefsResponse(statementId, accountId, null, null, null, null, null, null, null, null, null, null);
    }

    public static InboxRefsResponse ofAccount(UUID accountId) {
        return new InboxRefsResponse(null, accountId, null, null, null, null, null, null, null, null, null, null);
    }

    public static InboxRefsResponse ofLoan(UUID loanId) {
        return new InboxRefsResponse(null, null, loanId, null, null, null, null, null, null, null, null, null);
    }

    public static InboxRefsResponse ofCounterparty(UUID counterpartyId) {
        return new InboxRefsResponse(null, null, null, counterpartyId, null, null, null, null, null, null, null, null);
    }

    public static InboxRefsResponse ofConnection(UUID connectionId) {
        return new InboxRefsResponse(null, null, null, null, connectionId, null, null, null, null, null, null, null);
    }

    public static InboxRefsResponse ofJob(UUID jobId) {
        return new InboxRefsResponse(null, null, null, null, null, jobId, null, null, null, null, null, null);
    }

    public static InboxRefsResponse ofEpfMatch(UUID epfEntryId, UUID transactionId) {
        return new InboxRefsResponse(null, null, null, null, null, null, epfEntryId, transactionId, null, null, null, null);
    }

    public static InboxRefsResponse ofNpsPending(UUID npsPendingId) {
        return new InboxRefsResponse(null, null, null, null, null, null, null, null, npsPendingId, null, null, null);
    }

    public static InboxRefsResponse ofNpsMatch(UUID npsEntryId, UUID transactionId) {
        return new InboxRefsResponse(null, null, null, null, null, null, null, transactionId, null, npsEntryId, null, null);
    }

    public static InboxRefsResponse ofNpsRecord(UUID transactionId) {
        return new InboxRefsResponse(null, null, null, null, null, null, null, transactionId, null, null, null, null);
    }

    public static InboxRefsResponse ofCorporateActionSuggestion(UUID suggestionId, UUID instrumentId) {
        return new InboxRefsResponse(null, null, null, null, null, null, null, null, null, null, suggestionId, instrumentId);
    }

    public static InboxRefsResponse ofInstrument(UUID instrumentId) {
        return new InboxRefsResponse(null, null, null, null, null, null, null, null, null, null, null, instrumentId);
    }
}
