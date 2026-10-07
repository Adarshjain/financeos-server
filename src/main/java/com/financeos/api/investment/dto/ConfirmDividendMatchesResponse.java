package com.financeos.api.investment.dto;

import java.util.List;
import java.util.UUID;

/** Partial success is reported, never hidden: every item is either in {@code linked} or in {@code skipped}. */
public record ConfirmDividendMatchesResponse(
        List<DividendResponse> linked,
        List<Skipped> skipped
) {
    public record Skipped(
            UUID dividendId,
            String reason
    ) {}
}
