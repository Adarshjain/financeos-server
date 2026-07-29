package com.financeos.domain.instrument.price;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record PriceRefreshResult(
        int refreshed,
        int skipped,
        List<FailedItem> failed,
        LocalDate asOf
) {
    public record FailedItem(
            UUID instrumentId,
            String instrumentName,
            String reason
    ) {}
}
