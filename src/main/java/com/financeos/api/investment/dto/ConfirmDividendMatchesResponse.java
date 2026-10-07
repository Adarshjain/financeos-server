package com.financeos.api.investment.dto;

import java.util.List;
import java.util.UUID;

/** Partial success is reported, never hidden: every item is either in {@code linked} or in {@code skipped}. */
public record ConfirmDividendMatchesResponse(
        List<DividendResponse> linked,
        List<SkippedDividendMatch> skipped
) {
    public record SkippedDividendMatch(
            UUID dividendId,
            String reason
    ) {}
}
