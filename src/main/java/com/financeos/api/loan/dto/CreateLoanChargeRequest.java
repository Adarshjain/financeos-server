package com.financeos.api.loan.dto;

import com.financeos.domain.loan.LoanChargeType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record CreateLoanChargeRequest(
        @NotNull LoanChargeType chargeType,
        @NotNull @Positive BigDecimal amount,
        @NotNull LocalDate chargeDate,
        UUID transactionId,
        String notes
) {}
