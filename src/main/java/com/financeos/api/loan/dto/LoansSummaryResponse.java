package com.financeos.api.loan.dto;

import java.math.BigDecimal;

public record LoansSummaryResponse(
        BigDecimal totalOutstanding,
        long activeLoanCount,
        BigDecimal lentOutstanding,
        BigDecimal borrowedOutstanding,
        BigDecimal netReceivable
) {}
