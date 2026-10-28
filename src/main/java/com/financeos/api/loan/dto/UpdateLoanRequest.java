package com.financeos.api.loan.dto;

import com.financeos.domain.loan.LoanType;
import com.financeos.domain.loan.RateType;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record UpdateLoanRequest(
        String name,
        LoanType loanType,
        String lender,
        String loanAccountNumber,
        UUID paymentAccountId,
        @Schema(description = "true removes the loan's payment account; null/false keeps it. Cannot be combined with paymentAccountId.")
        Boolean clearPaymentAccount,
        BigDecimal principal,
        BigDecimal annualRatePct,
        RateType rateType,
        Integer tenureMonths,
        LocalDate startDate,
        LocalDate firstEmiDate,
        BigDecimal emiAmount,
        String notes
) {}
