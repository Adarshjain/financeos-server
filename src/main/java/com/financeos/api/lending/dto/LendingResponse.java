package com.financeos.api.lending.dto;

import com.financeos.domain.lending.Lending;
import com.financeos.domain.lending.LendingDirection;

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
        BigDecimal amount,
        LocalDate entryDate,
        @Nullable LocalDate expectedReturnDate,
        @Nullable UUID transactionId,
        @Nullable String notes,
        Instant createdAt
) {
    public static LendingResponse from(Lending lending) {
        return new LendingResponse(
                lending.getId(),
                lending.getCounterparty().getId(),
                lending.getCounterparty().getName(),
                lending.getDirection(),
                lending.getAmount(),
                lending.getEntryDate(),
                lending.getExpectedReturnDate(),
                lending.getTransaction() != null ? lending.getTransaction().getId() : null,
                lending.getNotes(),
                lending.getCreatedAt()
        );
    }
}
