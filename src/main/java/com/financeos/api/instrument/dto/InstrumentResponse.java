package com.financeos.api.instrument.dto;

import com.financeos.domain.instrument.AssetClass;
import com.financeos.domain.instrument.AssetClassSource;
import com.financeos.domain.instrument.AssetClassifier;
import com.financeos.domain.instrument.Instrument;
import com.financeos.domain.instrument.InstrumentOverrides;
import com.financeos.domain.instrument.InstrumentPrice;
import com.financeos.domain.instrument.InstrumentType;
import com.financeos.domain.instrument.PriceSource;
import com.financeos.domain.instrument.TaxClass;

import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public record InstrumentResponse(
        UUID id,
        InstrumentType type,
        String name,
        @Nullable String symbol,
        @Nullable String exchange,
        @Nullable String isin,
        @Nullable String amfiCode,
        @Nullable String yahooSymbol,
        String currency,
        @Nullable BigDecimal lastPrice,
        @Nullable LocalDate lastPriceAsOf,
        @Nullable PriceSource lastPriceSource,
        Instant createdAt,
        Instant updatedAt,
        /**
         * Effective asset class for the requesting user: their own override when they set one
         * (PATCH /instruments/{id}), else the stored global class, else the AMFI/name rules applied now.
         */
        AssetClass assetClass,
        /**
         * MANUAL when the value above is the requesting user's own override; else AMFI | RULE for the
         * global class, or null while it is still unset (the value above is then derived).
         */
        @Nullable AssetClassSource assetClassSource,
        /** Mutual funds: the AMFI scheme-category header; null otherwise or when unknown. */
        @Nullable String schemeCategory,
        /** Capital-gains tax class that goes with the asset class. */
        TaxClass taxClass,
        /**
         * Whether the requesting user edited this instrument for their own account (any field in
         * {@code overriddenFields}); the values above are then theirs, not the shared catalog's.
         */
        boolean overridden,
        /**
         * The fields the requesting user overrode: any of name, symbol, exchange, currency, type,
         * assetClass (empty when none). DELETE /instruments/{id}/overrides resets them all.
         */
        List<String> overriddenFields,
        /**
         * Only on the answer to an identifier edit (PUT /instruments/{id}): true when the edit merged a
         * holding into one the user already had of this instrument at the same broker. Else false.
         */
        boolean mergedHoldings,
        /**
         * Set with {@code mergedHoldings} when both merged holdings had sells: their trades are now one
         * FIFO history, so realised gains (and the lots still open) may differ from before. Else null.
         */
        @Nullable String mergeNote
) {
    /** The instrument with its global classification (no per-user override). */
    public static InstrumentResponse from(Instrument instrument, Optional<InstrumentPrice> latestPrice) {
        return from(instrument, latestPrice, InstrumentOverrides.NONE);
    }

    /** The instrument as one user sees it with only an asset-class {@code override} (nullable) applied. */
    public static InstrumentResponse from(Instrument instrument, Optional<InstrumentPrice> latestPrice,
                                          @Nullable AssetClass override) {
        return from(instrument, latestPrice, InstrumentOverrides.NONE.withAssetClass(instrument.getId(), override));
    }

    /**
     * The instrument as one user sees it: their {@code overrides} of the display fields and asset class
     * applied over the catalog, with {@code latestPrice} the latest price they see.
     */
    public static InstrumentResponse from(Instrument instrument, Optional<InstrumentPrice> latestPrice,
                                          InstrumentOverrides overrides) {
        AssetClassifier.Classification classification = overrides.classification(instrument);
        List<String> fields = overrides.overriddenFields(instrument.getId());
        return new InstrumentResponse(
                instrument.getId(),
                overrides.type(instrument),
                overrides.name(instrument),
                overrides.symbol(instrument),
                overrides.exchange(instrument),
                instrument.getIsin(),
                instrument.getAmfiCode(),
                instrument.getYahooSymbol(),
                overrides.currency(instrument),
                latestPrice.map(InstrumentPrice::getClose).orElse(null),
                latestPrice.map(InstrumentPrice::getAsOf).orElse(null),
                latestPrice.map(InstrumentPrice::getSource).orElse(null),
                instrument.getCreatedAt(),
                instrument.getUpdatedAt(),
                classification.assetClass(),
                overrides.assetClass(instrument.getId()) != null ? AssetClassSource.MANUAL : instrument.getAssetClassSource(),
                instrument.getSchemeCategory(),
                classification.taxClass(),
                !fields.isEmpty(),
                fields,
                false,
                null
        );
    }

    /** This response with the merge outcome of an identifier edit ({@code mergedHoldings}, {@code mergeNote}). */
    public InstrumentResponse withMerge(boolean merged, @Nullable String note) {
        return new InstrumentResponse(id, type, name, symbol, exchange, isin, amfiCode, yahooSymbol, currency,
                lastPrice, lastPriceAsOf, lastPriceSource, createdAt, updatedAt, assetClass, assetClassSource,
                schemeCategory, taxClass, overridden, overriddenFields, merged, merged ? note : null);
    }

}
