package com.financeos.api.investment.dto;

import com.financeos.domain.instrument.OptionType;
import com.financeos.domain.investment.fno.FnoContractType;

import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.time.LocalDate;

public record FnoTradePreviewDto(
        String tradingSymbol,
        String underlyingSymbol,
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
        String externalRef,
        boolean isDuplicate
) {}
