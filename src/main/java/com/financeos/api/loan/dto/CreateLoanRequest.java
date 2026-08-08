package com.financeos.api.loan.dto;

import com.financeos.domain.loan.LoanType;
import com.financeos.domain.loan.RateType;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record CreateLoanRequest(
        @NotBlank String name,
        @NotNull LoanType loanType,
        @NotBlank String lender,
        String loanAccountNumber,
        UUID paymentAccountId,
        @NotNull @Positive BigDecimal principal,
        @NotNull @DecimalMin("0.0001") @DecimalMax("60.0000") BigDecimal annualRatePct,
        @NotNull RateType rateType,
        @NotNull @Min(1) @Max(600) Integer tenureMonths,
        @NotNull LocalDate startDate,
        @NotNull LocalDate firstEmiDate,
        @Positive BigDecimal emiAmount,
        String notes
) {}
