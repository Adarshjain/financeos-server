package com.financeos.domain.investment;

import com.financeos.api.investment.dto.PositionDto;
import com.financeos.domain.account.Account;
import com.financeos.domain.holding.Holding;
import com.financeos.domain.instrument.InstrumentOverrides;
import com.financeos.domain.instrument.AssetClassifier;
import com.financeos.domain.instrument.Instrument;
import com.financeos.domain.instrument.PriceSource;

import java.math.BigDecimal;
import java.time.LocalDate;

public record HoldingPosition(
        Holding holding,
        BigDecimal openQty,
        BigDecimal avgCost,
        BigDecimal openCost,
        BigDecimal latestPrice,
        LocalDate priceAsOf,
        PriceSource priceSource,
        BigDecimal currentValue,
        BigDecimal unrealized,
        BigDecimal unrealizedPercent,
        BigDecimal realized,
        BigDecimal intradayRealized,
        BigDecimal totalCharges,
        BigDecimal dividends,
        Double xirr,
        BigDecimal absoluteReturnPercent,
        String mergedIntoName,
        LocalDate mergedIntoDate,
        BigDecimal buyQty,
        BigDecimal buyValue,
        BigDecimal avgBuy,
        BigDecimal sellQty,
        BigDecimal sellValue,
        BigDecimal avgSell,
        BigDecimal netQty,
        Boolean unclosed
) {
    public HoldingPosition(
            Holding holding,
            BigDecimal openQty,
            BigDecimal avgCost,
            BigDecimal openCost,
            BigDecimal latestPrice,
            LocalDate priceAsOf,
            PriceSource priceSource,
            BigDecimal currentValue,
            BigDecimal unrealized,
            BigDecimal unrealizedPercent,
            BigDecimal realized,
            BigDecimal intradayRealized,
            BigDecimal totalCharges,
            BigDecimal dividends,
            Double xirr,
            BigDecimal absoluteReturnPercent
    ) {
        this(holding, openQty, avgCost, openCost, latestPrice, priceAsOf, priceSource, currentValue, unrealized, unrealizedPercent, realized, intradayRealized, totalCharges, dividends, xirr, absoluteReturnPercent, null, null, null, null, null, null, null, null, null, null);
    }

    public HoldingPosition(
            Holding holding,
            BigDecimal openQty,
            BigDecimal avgCost,
            BigDecimal openCost,
            BigDecimal latestPrice,
            LocalDate priceAsOf,
            PriceSource priceSource,
            BigDecimal currentValue,
            BigDecimal unrealized,
            BigDecimal unrealizedPercent,
            BigDecimal realized,
            BigDecimal intradayRealized,
            BigDecimal totalCharges,
            BigDecimal dividends,
            Double xirr,
            BigDecimal absoluteReturnPercent,
            String mergedIntoName,
            LocalDate mergedIntoDate
    ) {
        this(holding, openQty, avgCost, openCost, latestPrice, priceAsOf, priceSource, currentValue, unrealized, unrealizedPercent, realized, intradayRealized, totalCharges, dividends, xirr, absoluteReturnPercent, mergedIntoName, mergedIntoDate, null, null, null, null, null, null, null, null);
    }

    /** The position with the shared catalog's view of the instrument (no per-user override). */
    public PositionDto toPositionDto() {
        return toPositionDto(InstrumentOverrides.NONE);
    }

    /**
     * The position as its owner sees it: their {@code overrides} of the instrument's display fields
     * and asset class applied (the identifiers stay the catalog's — they pick the price feed).
     */
    public PositionDto toPositionDto(InstrumentOverrides overrides) {
        Account b = holding.getBrokerAccount();
        String provider = b.getBrokerDetails() != null ? b.getBrokerDetails().getProvider() : null;

        Instrument inst = holding.getInstrument();
        AssetClassifier.Classification classification = overrides.classification(inst);
        PositionDto.InstrumentInfoDto instInfo = new PositionDto.InstrumentInfoDto(
                inst.getId(),
                overrides.type(inst),
                overrides.name(inst),
                overrides.symbol(inst),
                inst.getIsin(),
                inst.getAmfiCode(),
                inst.getYahooSymbol(),
                priceSource
        );

        return new PositionDto(
                holding.getId(),
                b.getId(),
                b.getName(),
                provider,
                instInfo,
                openQty,
                avgCost,
                openCost,
                latestPrice,
                priceAsOf,
                priceSource,
                currentValue,
                unrealized,
                unrealizedPercent,
                realized,
                intradayRealized,
                dividends,
                xirr,
                absoluteReturnPercent,
                totalCharges,
                holding.getNotes(),
                mergedIntoName,
                mergedIntoDate,
                buyQty,
                buyValue,
                avgBuy,
                sellQty,
                sellValue,
                avgSell,
                netQty,
                unclosed,
                classification.assetClass(),
                classification.taxClass(),
                null,
                null,
                null,
                null
        );
    }
}
