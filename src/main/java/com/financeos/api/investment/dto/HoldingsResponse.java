package com.financeos.api.investment.dto;

import com.financeos.domain.instrument.InstrumentType;
import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * The holdings list: one row per instrument (its holdings across broker accounts summed), the header
 * and group totals over the whole filtered set, and one page of rows. {@code movers}: the (up to) three
 * biggest day moves in rupees over the whole filtered set, biggest first; {@code portfolioGroups}: the groups
 * ignoring the type and search filters (the broker filter kept), for the allocation view.
 */
public record HoldingsResponse(
        Header header,
        List<Group> groups,
        List<Row> holdings,
        int page,
        int size,
        long totalElements,
        int totalPages,
        List<Mover> movers,
        List<Group> portfolioGroups
) {
    /** One of today's biggest rupee day moves among the filtered holdings (all pages); {@code dayChange} is rounded to paise. */
    public record Mover(UUID instrumentId, @Nullable String symbol, String name, BigDecimal dayChange) {}

    /** Totals over every filtered holding (all pages). {@code xirr} is percent; null when not computable. */
    public record Header(
            BigDecimal currentValue,
            @Nullable BigDecimal dayChange,
            @Nullable BigDecimal dayChangePct,
            BigDecimal invested,
            BigDecimal unrealised,
            @Nullable BigDecimal unrealisedPct,
            @Nullable Double xirr,
            @Nullable LocalDate priceAsOf,
            @Nullable OffsetDateTime priceAt
    ) {}

    /** Current value of the filtered open holdings of one asset type. */
    public record Group(InstrumentType assetType, BigDecimal value, int count) {}

    /**
     * {@code manualPrice}: ltp is the user's own price; {@code noPrice}: an open holding without any
     * price (valued at cost). Day change is null without a current previous close.
     */
    public record Row(
            UUID instrumentId,
            @Nullable String symbol,
            String name,
            InstrumentType assetType,
            BigDecimal quantity,
            BigDecimal avgCost,
            @Nullable BigDecimal ltp,
            @Nullable LocalDate priceAsOf,
            @Nullable OffsetDateTime priceAt,
            boolean manualPrice,
            boolean noPrice,
            BigDecimal currentValue,
            BigDecimal invested,
            BigDecimal unrealised,
            @Nullable BigDecimal unrealisedPct,
            @Nullable BigDecimal dayChange,
            @Nullable BigDecimal dayChangePct,
            int brokerCount,
            @Nullable String mergedIntoName,
            @Nullable LocalDate mergedIntoDate
    ) {}
}
