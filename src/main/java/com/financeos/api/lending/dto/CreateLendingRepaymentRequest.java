package com.financeos.api.lending.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record CreateLendingRepaymentRequest(
        @NotNull @Positive BigDecimal amount,
        @NotNull LocalDate date,
        UUID transactionId
) {}
