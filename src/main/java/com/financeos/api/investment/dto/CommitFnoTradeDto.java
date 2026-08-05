package com.financeos.api.investment.dto;

import com.financeos.domain.instrument.OptionType;
import com.financeos.domain.investment.fno.FnoContractType;

import java.math.BigDecimal;
import java.time.LocalDate;

public record CommitFnoTradeDto(
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
        LocalDate entryDate,
        LocalDate exitDate,
        String externalRef,
        boolean skip
) {}
