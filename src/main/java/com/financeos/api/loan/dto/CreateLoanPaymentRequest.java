package com.financeos.api.loan.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record CreateLoanPaymentRequest(
        @NotNull LocalDate paymentDate,
        @NotNull @Positive BigDecimal amount,
        Integer installmentSeq,
        UUID transactionId
) {}
