package com.financeos.api.investment.dto;

import com.financeos.api.transaction.dto.TransactionResponse;

import org.springframework.lang.Nullable;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Bank credits that look like dividend payouts but are not linked to any dividend row (nor to a
 * loan/lending record or a transaction link). Each carries the holdings whose instrument name the
 * narration resembles, best first, so the client can prefill a "record dividend" form.
 */
public record UnrecordedDividendCreditsResponse(
        List<Item> items,
        LocalDate from,
        LocalDate to
) {
    public record Item(
            TransactionResponse transaction,
            List<HoldingHint> holdingHints
    ) {}

    public record HoldingHint(
            UUID holdingId,
            UUID brokerAccountId,
            String brokerName,
            UUID instrumentId,
            String instrumentName,
            @Nullable String symbol,
            double nameScore
    ) {}
}
