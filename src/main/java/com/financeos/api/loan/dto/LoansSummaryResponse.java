package com.financeos.api.loan.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record LoansSummaryResponse(
        BigDecimal totalOutstanding,
        long activeLoanCount,
        BigDecimal lentOutstanding,
        BigDecimal borrowedOutstanding,
        BigDecimal netReceivable,
        BigDecimal borrowedPrincipal,
        BigDecimal monthlyEmiTotal,
        List<Integer> emiDueDays,
        BigDecimal overdueAmount,
        long overdueCount,
        BigDecimal interestPaidThisFy,
        LocalDate fyStart,
        long closedLoanCount
) {}
