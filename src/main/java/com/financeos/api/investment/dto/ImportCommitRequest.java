package com.financeos.api.investment.dto;

import com.financeos.domain.instrument.InstrumentType;
import com.financeos.domain.investment.InvestmentTransactionType;
import com.financeos.domain.investment.imports.ImportSource;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record ImportCommitRequest(
        @NotNull(message = "Import source is required") ImportSource source,
        @NotNull(message = "Broker account ID is required") UUID brokerAccountId,
        @NotNull(message = "Commit rows list is required") List<CommitRowDto> rows
) {
    public record CommitRowDto(
            int rowIndex,
            boolean skip,
            UUID instrumentId,
            CreateInstrumentDto newInstrument,
            ParsedRowData row
    ) {}

    public record CreateInstrumentDto(
            InstrumentType type,
            String name,
            String symbol,
            String exchange,
            String isin,
            String amfiCode,
            String yahooSymbol
    ) {}

    public record ParsedRowData(
            InvestmentTransactionType type,
            BigDecimal quantity,
            BigDecimal price,
            LocalDate tradeDate,
            ItemizedChargesDto charges,
            String externalRef,
            String notes
    ) {}
}
