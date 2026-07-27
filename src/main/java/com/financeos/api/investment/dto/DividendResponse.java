package com.financeos.api.investment.dto;

import com.financeos.domain.holding.Holding;
import com.financeos.domain.investment.dividend.Dividend;
import com.financeos.domain.investment.dividend.DividendType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record DividendResponse(
        UUID id,
        UUID holdingId,
        UUID brokerAccountId,
        String brokerName,
        UUID instrumentId,
        String instrumentName,
        String symbol,
        DividendType type,
        BigDecimal amount,
        BigDecimal perUnit,
        BigDecimal tds,
        LocalDate exDate,
        LocalDate payDate,
        String source,
        String notes,
        Instant createdAt
) {
    public static DividendResponse from(Dividend dividend) {
        Holding h = dividend.getHolding();
        return new DividendResponse(
                dividend.getId(),
                h.getId(),
                h.getBrokerAccount().getId(),
                h.getBrokerAccount().getName(),
                h.getInstrument().getId(),
                h.getInstrument().getName(),
                h.getInstrument().getSymbol(),
                dividend.getType(),
                dividend.getAmount(),
                dividend.getPerUnit(),
                dividend.getTds(),
                dividend.getExDate(),
                dividend.getPayDate(),
                dividend.getSource(),
                dividend.getNotes(),
                dividend.getCreatedAt()
        );
    }
}
