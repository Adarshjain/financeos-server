package com.financeos.api.investment.dto;

import com.financeos.domain.investment.dividend.DividendReceiptStatus;

import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Dividend rows bucketed by derived receipt status (one bucket per status, zeros included so the
 * client strip is stable).
 *
 * @param coverageEnd latest transaction date on any tracked bank account (null = no bank data)
 */
public record DividendReceiptSummaryResponse(
        List<DividendReceiptBucket> buckets,
        @Nullable LocalDate coverageEnd,
        long totalCount
) {
    public record DividendReceiptBucket(
            DividendReceiptStatus status,
            long count,
            /** Σ (gross − recorded TDS) of the rows in this bucket. */
            BigDecimal expectedNet,
            /** Σ linked credit amounts (non-zero only for {@code received}). */
            BigDecimal receivedAmount
    ) {}
}
