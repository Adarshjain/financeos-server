package com.financeos.domain.investment;

import com.financeos.api.investment.dto.PositionDto;
import com.financeos.domain.account.Account;
import com.financeos.domain.holding.Holding;
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
        BigDecimal absoluteReturnPercent
) {
    PositionDto toPositionDto() {
        Account b = holding.getBrokerAccount();
        String provider = b.getBrokerDetails() != null ? b.getBrokerDetails().getProvider() : null;

        PositionDto.InstrumentInfoDto instInfo = new PositionDto.InstrumentInfoDto(
                holding.getInstrument().getId(),
                holding.getInstrument().getType(),
                holding.getInstrument().getName(),
                holding.getInstrument().getSymbol(),
                holding.getInstrument().getIsin(),
                holding.getInstrument().getAmfiCode(),
                holding.getInstrument().getYahooSymbol(),
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
                holding.getNotes()
        );
    }
}
