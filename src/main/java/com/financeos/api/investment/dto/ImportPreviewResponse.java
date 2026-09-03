package com.financeos.api.investment.dto;

import com.financeos.domain.instrument.InstrumentType;
import com.financeos.domain.investment.imports.ParsedRow;

import org.springframework.lang.Nullable;

import java.util.List;
import java.util.UUID;

public record ImportPreviewResponse(
        List<ImportRowPreviewDto> rows,
        SummaryDto summary
) {
    public record ImportRowPreviewDto(
            int rowIndex,
            String matchStatus, // "matched" | "unmatched"
            @Nullable MatchedInstrumentDto matchedInstrument,
            boolean duplicate,
            ParsedRow parsedRow
    ) {}

    public record MatchedInstrumentDto(
            UUID id,
            InstrumentType type,
            String name,
            @Nullable String symbol,
            @Nullable String exchange,
            @Nullable String isin
    ) {}

    public record SummaryDto(
            int total,
            int matched,
            int unmatched,
            int duplicates,
            int errors,
            @Nullable String note
    ) {}
}
