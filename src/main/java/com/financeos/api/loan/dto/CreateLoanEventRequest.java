package com.financeos.api.loan.dto;

import com.financeos.domain.loan.AdjustmentMode;
import com.financeos.domain.loan.LoanEventType;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record CreateLoanEventRequest(
        @NotNull LoanEventType eventType,
        @NotNull LocalDate effectiveDate,
        BigDecimal newAnnualRatePct,
        BigDecimal amount,
        AdjustmentMode adjustmentMode,
        BigDecimal newEmiOverride,
        UUID transactionId
) {}
