package com.financeos.api.investment.dto;

import com.financeos.domain.instrument.InstrumentType;
import com.financeos.domain.investment.InvestmentTransactionType;
import com.financeos.domain.investment.imports.ImportSource;
import jakarta.validation.constraints.NotNull;
import org.springframework.lang.Nullable;

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
            @Nullable UUID instrumentId,
            @Nullable CreateInstrumentDto newInstrument,
            @Nullable ParsedRowData row
    ) {}

    public record CreateInstrumentDto(
            @Nullable InstrumentType type,
            @Nullable String name,
            @Nullable String symbol,
            @Nullable String exchange,
            @Nullable String isin,
            @Nullable String amfiCode,
            @Nullable String yahooSymbol
    ) {}

    public record ParsedRowData(
            String kind,
            InvestmentTransactionType type,
            BigDecimal quantity,
            BigDecimal price,
            BigDecimal amount,
            LocalDate tradeDate,
            ItemizedChargesDto charges,
            String externalRef,
            String notes
    ) {}
}
