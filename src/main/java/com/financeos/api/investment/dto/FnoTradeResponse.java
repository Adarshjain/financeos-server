package com.financeos.api.investment.dto;

import com.financeos.domain.instrument.OptionType;
import com.financeos.domain.investment.fno.FnoContractType;
import com.financeos.domain.investment.fno.FnoTrade;

import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record FnoTradeResponse(
        UUID id,
        UUID brokerAccountId,
        String brokerAccountName,
        String tradingSymbol,
        @Nullable String underlyingSymbol,
        FnoContractType contractType,
        @Nullable OptionType optionType,
        @Nullable BigDecimal strikePrice,
        @Nullable LocalDate expiryDate,
        BigDecimal quantity,
        BigDecimal buyValue,
        BigDecimal sellValue,
        BigDecimal totalCharges,
        BigDecimal realizedPnl,
        @Nullable LocalDate entryDate,
        @Nullable LocalDate exitDate,
        String source,
        @Nullable String externalRef,
        @Nullable String notes,
        Instant createdAt
) {
    public static FnoTradeResponse from(FnoTrade trade) {
        return new FnoTradeResponse(
                trade.getId(),
                trade.getBrokerAccount() != null ? trade.getBrokerAccount().getId() : null,
                trade.getBrokerAccount() != null ? trade.getBrokerAccount().getName() : null,
                trade.getTradingSymbol(),
                trade.getUnderlyingSymbol(),
                trade.getContractType(),
                trade.getOptionType(),
                trade.getStrikePrice(),
                trade.getExpiryDate(),
                trade.getQuantity(),
                trade.getBuyValue(),
                trade.getSellValue(),
                trade.getTotalCharges(),
                trade.getRealizedPnl(),
                trade.getEntryDate(),
                trade.getExitDate(),
                trade.getSource(),
                trade.getExternalRef(),
                trade.getNotes(),
                trade.getCreatedAt()
        );
    }
}
