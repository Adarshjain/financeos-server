package com.financeos.domain.investment.imports;

import com.financeos.api.investment.dto.ItemizedChargesDto;
import com.financeos.domain.investment.InvestmentTransactionType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

public record ParsedRow(
        int rowIndex,
        String kind,
        InvestmentTransactionType type,
        String parsedSymbol,
        String parsedIsin,
        String parsedName,
        String exchange,
        BigDecimal quantity,
        BigDecimal price,
        BigDecimal amount,
        LocalDate tradeDate,
        ItemizedChargesDto charges,
        String externalRef,
        Map<String, String> rawData,
        String error
) {
    public ParsedRow(
            int rowIndex,
            String kind,
            InvestmentTransactionType type,
            String parsedSymbol,
            String parsedIsin,
            String parsedName,
            String exchange,
            BigDecimal quantity,
            BigDecimal price,
            LocalDate tradeDate,
            ItemizedChargesDto charges,
            String externalRef,
            Map<String, String> rawData,
            String error
    ) {
        this(rowIndex, kind, type, parsedSymbol, parsedIsin, parsedName, exchange, quantity, price, null, tradeDate, charges, externalRef, rawData, error);
    }
}
