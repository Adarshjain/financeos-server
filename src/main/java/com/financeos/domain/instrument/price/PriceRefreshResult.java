package com.financeos.domain.instrument.price;

import com.financeos.domain.instrument.PriceSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record PriceRefreshResult(
        int refreshed,
        int skipped,
        List<FailedItem> failed,
        LocalDate asOf,
        List<RefreshedItem> refreshedItems,
        List<SkippedItem> skippedItems
) {
    public record FailedItem(
            UUID instrumentId,
            String instrumentName,
            String reason
    ) {}

    /** A price written by this run: the instrument, the close and the date it is for, and who supplied it. */
    public record RefreshedItem(
            UUID instrumentId,
            String instrumentName,
            BigDecimal close,
            LocalDate priceDate,
            PriceSource source
    ) {}

    public record SkippedItem(
            UUID instrumentId,
            String instrumentName,
            String reason
    ) {}
}
