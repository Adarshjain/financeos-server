package com.financeos.api.lending.dto;

import com.financeos.domain.lending.LendingDirection;
import com.financeos.domain.lending.LendingKind;

import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** A {@link LendingResponse} plus the person's balance right after this entry (netPosition sign convention). */
public record LedgerEntryResponse(
        UUID id,
        UUID counterpartyId,
        String counterpartyName,
        LendingDirection direction,
        LendingKind kind,
        BigDecimal amount,
        LocalDate entryDate,
        @Nullable LocalDate expectedReturnDate,
        @Nullable UUID transactionId,
        @Nullable LendingTransactionSummary transaction,
        @Nullable String notes,
        Instant createdAt,
        @Nullable String returnNotifiedKind,
        @Nullable LocalDate returnNotifiedOn,
        BigDecimal runningBalance
) {
    public static LedgerEntryResponse from(LendingResponse l, BigDecimal runningBalance) {
        return new LedgerEntryResponse(l.id(), l.counterpartyId(), l.counterpartyName(), l.direction(), l.kind(),
                l.amount(), l.entryDate(), l.expectedReturnDate(), l.transactionId(), l.transaction(), l.notes(),
                l.createdAt(), l.returnNotifiedKind(), l.returnNotifiedOn(), runningBalance);
    }
}
