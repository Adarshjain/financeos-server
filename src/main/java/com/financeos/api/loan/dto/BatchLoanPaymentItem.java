package com.financeos.api.loan.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record BatchLoanPaymentItem(
        Integer installmentSeq,
        @NotNull LocalDate paymentDate,
        @NotNull @Positive BigDecimal amount,
        UUID transactionId
) {}
