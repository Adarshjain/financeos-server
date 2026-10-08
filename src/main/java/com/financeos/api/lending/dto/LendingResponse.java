package com.financeos.api.lending.dto;

import com.financeos.domain.lending.Lending;
import com.financeos.domain.lending.LendingDirection;
import com.financeos.domain.lending.LendingKind;

import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record LendingResponse(
        UUID id,
        UUID counterpartyId,
        String counterpartyName,
        LendingDirection direction,
        LendingKind kind,
        BigDecimal amount,
        LocalDate entryDate,
        @Nullable LocalDate expectedReturnDate,
        /** Kept for compatibility; prefer {@link #transaction}. */
        @Nullable UUID transactionId,
        /** The linked bank transaction, or null when the entry is unlinked. */
        @Nullable LendingTransactionSummary transaction,
        @Nullable String notes,
        Instant createdAt,
        /** Return-date reminder marker (DUE_0 / OVERDUE) for transparency. */
        @Nullable String returnNotifiedKind,
        @Nullable LocalDate returnNotifiedOn
) {
    public static LendingResponse from(Lending lending) {
        return new LendingResponse(
                lending.getId(),
                lending.getCounterparty().getId(),
                lending.getCounterparty().getName(),
                lending.getDirection(),
                lending.getKind(),
                lending.getAmount(),
                lending.getEntryDate(),
                lending.getExpectedReturnDate(),
                lending.getTransaction() != null ? lending.getTransaction().getId() : null,
                LendingTransactionSummary.from(lending.getTransaction()),
                lending.getNotes(),
                lending.getCreatedAt(),
                lending.getReturnNotifiedKind(),
                lending.getReturnNotifiedOn()
        );
    }
}
