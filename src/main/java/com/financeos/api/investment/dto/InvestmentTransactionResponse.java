package com.financeos.api.investment.dto;

import com.financeos.domain.holding.Holding;
import com.financeos.domain.instrument.InstrumentType;
import com.financeos.domain.investment.InvestmentTransaction;
import com.financeos.domain.investment.InvestmentTransactionType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record InvestmentTransactionResponse(
        UUID id,
        HoldingDto holding,
        InvestmentTransactionType type,
        BigDecimal quantity,
        BigDecimal price,
        LocalDate tradeDate,
        ItemizedChargesDto charges,
        BigDecimal totalCharges,
        String source,
        String externalRef,
        String notes,
        Instant createdAt
) {
    public record HoldingDto(
            UUID id,
            BrokerInfoDto broker,
            InstrumentInfoDto instrument
    ) {}

    public record BrokerInfoDto(
            UUID id,
            String name,
            String provider
    ) {}

    public record InstrumentInfoDto(
            UUID id,
            InstrumentType type,
            String name,
            String symbol
    ) {}

    public static InvestmentTransactionResponse from(InvestmentTransaction txn) {
        Holding h = txn.getHolding();
        String provider = h.getBrokerAccount().getBrokerDetails() != null ? h.getBrokerAccount().getBrokerDetails().getProvider() : null;

        HoldingDto holdingDto = new HoldingDto(
                h.getId(),
                new BrokerInfoDto(h.getBrokerAccount().getId(), h.getBrokerAccount().getName(), provider),
                new InstrumentInfoDto(h.getInstrument().getId(), h.getInstrument().getType(), h.getInstrument().getName(), h.getInstrument().getSymbol())
        );

        ItemizedChargesDto charges = new ItemizedChargesDto(
                txn.getBrokerage(),
                txn.getStt(),
                txn.getExchangeTxnCharges(),
                txn.getSebiCharges(),
                txn.getStampDuty(),
                txn.getGst(),
                txn.getDpCharges(),
                txn.getOtherCharges()
        );

        return new InvestmentTransactionResponse(
                txn.getId(),
                holdingDto,
                txn.getType(),
                txn.getQuantity(),
                txn.getPrice(),
                txn.getTradeDate(),
                charges,
                txn.getTotalCharges() != null ? txn.getTotalCharges() : BigDecimal.ZERO,
                txn.getSource(),
                txn.getExternalRef(),
                txn.getNotes(),
                txn.getCreatedAt()
        );
    }
}
