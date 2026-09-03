package com.financeos.api.investment.dto;

import com.financeos.domain.holding.Holding;
import com.financeos.domain.instrument.InstrumentType;
import com.financeos.domain.investment.InvestmentTransaction;
import com.financeos.domain.investment.InvestmentTransactionType;
import com.financeos.domain.investment.SettlementType;

import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record InvestmentTransactionResponse(
        UUID id,
        UUID brokerAccountId,
        String brokerName,
        @Nullable String provider,
        UUID instrumentId,
        InstrumentInfoDto instrument,
        InvestmentTransactionType type,
        SettlementType settlementType,
        BigDecimal quantity,
        BigDecimal price,
        LocalDate tradeDate,
        @Nullable BigDecimal brokerage,
        @Nullable BigDecimal stt,
        @Nullable BigDecimal exchangeTxnCharges,
        @Nullable BigDecimal sebiCharges,
        @Nullable BigDecimal stampDuty,
        @Nullable BigDecimal gst,
        @Nullable BigDecimal dpCharges,
        @Nullable BigDecimal otherCharges,
        BigDecimal totalCharges,
        @Nullable String source,
        @Nullable String externalRef,
        @Nullable String notes,
        Instant createdAt
) {
    public record InstrumentInfoDto(
            UUID id,
            InstrumentType type,
            String name,
            @Nullable String symbol
    ) {}

    public static InvestmentTransactionResponse from(InvestmentTransaction txn) {
        Holding h = txn.getHolding();
        String provider = h.getBrokerAccount().getBrokerDetails() != null ? h.getBrokerAccount().getBrokerDetails().getProvider() : null;

        InstrumentInfoDto instrumentDto = new InstrumentInfoDto(
                h.getInstrument().getId(),
                h.getInstrument().getType(),
                h.getInstrument().getName(),
                h.getInstrument().getSymbol()
        );

        return new InvestmentTransactionResponse(
                txn.getId(),
                h.getBrokerAccount().getId(),
                h.getBrokerAccount().getName(),
                provider,
                h.getInstrument().getId(),
                instrumentDto,
                txn.getType(),
                txn.getSettlementType(),
                txn.getQuantity(),
                txn.getPrice(),
                txn.getTradeDate(),
                txn.getBrokerage(),
                txn.getStt(),
                txn.getExchangeTxnCharges(),
                txn.getSebiCharges(),
                txn.getStampDuty(),
                txn.getGst(),
                txn.getDpCharges(),
                txn.getOtherCharges(),
                txn.getTotalCharges() != null ? txn.getTotalCharges() : BigDecimal.ZERO,
                txn.getSource(),
                txn.getExternalRef(),
                txn.getNotes(),
                txn.getCreatedAt()
        );
    }
}
