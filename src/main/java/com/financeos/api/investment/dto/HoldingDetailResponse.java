package com.financeos.api.investment.dto;

import com.financeos.domain.instrument.InstrumentType;
import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * One instrument across the user's broker accounts: the list row's figures plus realised P&amp;L,
 * charges, XIRR, first buy and the term of what is still held.
 * {@code term}: long | short | slab when every open lot has it, mixed otherwise, null with no open lots.
 */
public record HoldingDetailResponse(
        UUID instrumentId,
        @Nullable String symbol,
        String name,
        InstrumentType assetType,
        BigDecimal quantity,
        BigDecimal avgCost,
        @Nullable BigDecimal ltp,
        @Nullable LocalDate priceAsOf,
        boolean manualPrice,
        boolean noPrice,
        BigDecimal currentValue,
        BigDecimal invested,
        BigDecimal unrealised,
        @Nullable BigDecimal unrealisedPct,
        @Nullable BigDecimal dayChange,
        @Nullable BigDecimal dayChangePct,
        @Nullable String mergedIntoName,
        @Nullable LocalDate mergedIntoDate,
        BigDecimal realisedPnl,
        BigDecimal totalCharges,
        BigDecimal dividends,
        @Nullable Double xirr,
        @Nullable LocalDate firstBuyDate,
        @Nullable String term,
        List<Broker> brokers
) {
    public record Broker(UUID brokerAccountId, String accountName, BigDecimal quantity, BigDecimal avgCost,
                         BigDecimal currentValue) {}
}
