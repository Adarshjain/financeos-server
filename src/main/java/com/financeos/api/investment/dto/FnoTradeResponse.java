package com.financeos.api.investment.dto;

import com.financeos.domain.instrument.OptionType;
import com.financeos.domain.investment.fno.FnoContractType;
import com.financeos.domain.investment.fno.FnoTrade;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record FnoTradeResponse(
        UUID id,
        UUID brokerAccountId,
        String brokerAccountName,
        String tradingSymbol,
        String underlyingSymbol,
        FnoContractType contractType,
        OptionType optionType,
        BigDecimal strikePrice,
        LocalDate expiryDate,
        BigDecimal quantity,
        BigDecimal buyValue,
        BigDecimal sellValue,
        BigDecimal totalCharges,
        BigDecimal realizedPnl,
        LocalDate entryDate,
        LocalDate exitDate,
        String source,
        String externalRef,
        String notes,
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
