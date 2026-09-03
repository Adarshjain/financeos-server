package com.financeos.api.loan.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record BatchLoanPaymentItem(
        @Nullable Integer installmentSeq,
        @NotNull LocalDate paymentDate,
        @NotNull @Positive BigDecimal amount,
        @Nullable UUID transactionId
) {}
