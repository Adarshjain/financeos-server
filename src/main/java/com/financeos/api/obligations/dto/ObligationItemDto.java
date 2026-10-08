package com.financeos.api.obligations.dto;

import com.financeos.domain.lending.LendingDirection;

import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One upcoming obligation. {@code type} is one of {@code emi | lending_due | card_bill |
 * statement_expected}; {@code status} is {@code overdue | due_soon | upcoming} (due soon = due
 * within the next 7 days). {@code date} and {@code amount} are null when unknown (a card bill
 * whose due date the parser missed, an expected statement whose amount is not yet known).
 */
public record ObligationItemDto(
        String type, // "emi" | "lending_due" | "card_bill" | "statement_expected"
        @Nullable LocalDate date,
        @Nullable BigDecimal amount,
        String status, // "upcoming" | "overdue" | "due_soon"
        @Nullable UUID loanId,
        @Nullable String loanName,
        @Nullable Integer installmentSeq,
        @Nullable UUID lendingId,
        @Nullable UUID counterpartyId,
        @Nullable String counterpartyName,
        @Nullable LendingDirection direction,
        @Nullable String title,
        @Nullable UUID accountId,
        @Nullable String accountName,
        @Nullable UUID statementId,
        @Nullable String href,
        @Nullable Long daysUntil
) {
    /**
     * The producer shape (EMI and lending items carry only their references); the obligations
     * service fills title, href, daysUntil and the final status before the row leaves the API.
     */
    public ObligationItemDto(String type,
                             @Nullable LocalDate date,
                             @Nullable BigDecimal amount,
                             String status,
                             @Nullable UUID loanId,
                             @Nullable String loanName,
                             @Nullable Integer installmentSeq,
                             @Nullable UUID lendingId,
                             @Nullable UUID counterpartyId,
                             @Nullable String counterpartyName,
                             @Nullable LendingDirection direction) {
        this(type, date, amount, status, loanId, loanName, installmentSeq, lendingId, counterpartyId,
                counterpartyName, direction, null, null, null, null, null, null);
    }
}
