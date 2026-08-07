package com.financeos.api.investment.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record DividendSummaryResponse(
        List<FyBucket> buckets,
        BigDecimal totalAmount,
        BigDecimal totalTds,
        BigDecimal totalNet,
        long totalCount
) {
    public record FyBucket(
            String label,
            LocalDate fromDate,
            LocalDate toDate,
            BigDecimal amount,
            BigDecimal tds,
            BigDecimal net,
            long count
    ) {}
}
