package com.financeos.domain.investment.dto;

import com.financeos.domain.instrument.InstrumentType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

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
        String term
) {}
