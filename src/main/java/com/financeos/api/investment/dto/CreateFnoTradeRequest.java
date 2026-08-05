package com.financeos.api.investment.dto;

import com.financeos.domain.instrument.OptionType;
import com.financeos.domain.investment.fno.FnoContractType;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record CreateFnoTradeRequest(
        @NotNull UUID brokerAccountId,
        @NotNull String tradingSymbol,
        String underlyingSymbol,
        FnoContractType contractType,
        OptionType optionType,
        BigDecimal strikePrice,
        LocalDate expiryDate,
        @NotNull BigDecimal quantity,
        @NotNull BigDecimal buyValue,
        @NotNull BigDecimal sellValue,
        BigDecimal totalCharges,
        LocalDate entryDate,
        LocalDate exitDate,
        String notes
) {}
