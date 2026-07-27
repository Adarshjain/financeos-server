package com.financeos.domain.instrument.price;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record PriceRefreshResult(
        int refreshedCount,
        int skippedCount,
        List<FailedItem> failedList,
        LocalDate asOf
) {
    public record FailedItem(
            UUID instrumentId,
            String symbol,
            String reason
    ) {}
}
