package com.financeos.api.lending.dto;

import com.financeos.domain.lending.LendingDirection;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record CreateLendingRequest(
        UUID counterpartyId,
        String newCounterpartyName,
        @NotNull LendingDirection direction,
        @NotNull @Positive BigDecimal amount,
        @NotNull LocalDate lendDate,
        LocalDate expectedReturnDate,
        UUID transactionId,
        String notes
) {}
