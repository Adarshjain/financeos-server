package com.financeos.api.loan.dto;

import com.financeos.domain.loan.LoanType;
import com.financeos.domain.loan.RateType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record UpdateLoanRequest(
        String name,
        LoanType loanType,
        String lender,
        String loanAccountNumber,
        UUID paymentAccountId,
        BigDecimal principal,
        BigDecimal annualRatePct,
        RateType rateType,
        Integer tenureMonths,
        LocalDate startDate,
        LocalDate firstEmiDate,
        BigDecimal emiAmount,
        String notes
) {}
