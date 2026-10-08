package com.financeos.api.inbox.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.lang.Nullable;

/** Everything a client needs to run an inbox action without a second lookup; only the relevant ids are set. */
public record InboxActionPayloadResponse(
        @Nullable UUID statementId,
        @Nullable UUID transactionId,
        @Nullable BigDecimal amount,
        @Nullable LocalDate date,
        @Nullable UUID accountId,
        @Nullable UUID loanId,
        @Nullable Integer installmentSeq,
        @Nullable UUID counterpartyId,
        @Nullable UUID connectionId,
        @Nullable UUID jobId
) {
    public static InboxActionPayloadResponse forStatement(UUID statementId, @Nullable BigDecimal amount) {
        return new InboxActionPayloadResponse(statementId, null, amount, null, null, null, null, null, null, null);
    }

    public static InboxActionPayloadResponse forPayment(UUID statementId, UUID transactionId, BigDecimal amount, LocalDate date) {
        return new InboxActionPayloadResponse(statementId, transactionId, amount, date, null, null, null, null, null, null);
    }

    public static InboxActionPayloadResponse forLoan(UUID loanId, Integer installmentSeq) {
        return new InboxActionPayloadResponse(null, null, null, null, null, loanId, installmentSeq, null, null, null);
    }

    public static InboxActionPayloadResponse forCounterparty(UUID counterpartyId) {
        return new InboxActionPayloadResponse(null, null, null, null, null, null, null, counterpartyId, null, null);
    }

    public static InboxActionPayloadResponse forAccount(UUID accountId) {
        return new InboxActionPayloadResponse(null, null, null, null, accountId, null, null, null, null, null);
    }

    public static InboxActionPayloadResponse forConnection(UUID connectionId) {
        return new InboxActionPayloadResponse(null, null, null, null, null, null, null, null, connectionId, null);
    }

    public static InboxActionPayloadResponse forJob(UUID jobId) {
        return new InboxActionPayloadResponse(null, null, null, null, null, null, null, null, null, jobId);
    }
}
