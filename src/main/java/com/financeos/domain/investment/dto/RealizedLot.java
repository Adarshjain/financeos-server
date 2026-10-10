package com.financeos.domain.investment.dto;

import com.financeos.domain.instrument.AssetClass;
import com.financeos.domain.instrument.InstrumentType;
import com.financeos.domain.instrument.TaxClass;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One FIFO-matched slice of a sell. {@code term} is short | long | slab per
 * {@link com.financeos.domain.investment.CapitalGainsTerm}; {@code grandfathered} marks equity bought
 * before 2018-02-01.
 */
public record RealizedLot(
        UUID holdingId,
        UUID brokerAccountId,
        String brokerName,
        UUID instrumentId,
        String instrumentName,
        InstrumentType instrumentType,
        LocalDate buyDate,
        LocalDate sellDate,
        BigDecimal quantity,
        BigDecimal buyValue,
        BigDecimal sellValue,
        BigDecimal realizedPnl,
        long holdingDays,
        String term,
        AssetClass assetClass,
        TaxClass taxClass,
        boolean grandfathered
) {
    /** This lot with the instrument shown under another name and type (a user's own overrides). */
    public RealizedLot withInstrument(String name, InstrumentType type) {
        if (java.util.Objects.equals(name, instrumentName) && type == instrumentType) {
            return this;
        }
        return new RealizedLot(holdingId, brokerAccountId, brokerName, instrumentId, name, type, buyDate, sellDate,
                quantity, buyValue, sellValue, realizedPnl, holdingDays, term, assetClass, taxClass, grandfathered);
    }
}
