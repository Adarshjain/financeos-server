package com.financeos.api.investment.dto;

import com.financeos.domain.instrument.InstrumentType;
import com.financeos.domain.investment.imports.ParsedRow;

import java.util.List;
import java.util.UUID;

public record ImportPreviewResponse(
        List<ImportRowPreviewDto> rows,
        SummaryDto summary
) {
    public record ImportRowPreviewDto(
            int rowIndex,
            String matchStatus, // "matched" | "unmatched"
            MatchedInstrumentDto matchedInstrument,
            boolean duplicate,
            ParsedRow parsedRow
    ) {}

    public record MatchedInstrumentDto(
            UUID id,
            InstrumentType type,
            String name,
            String symbol,
            String exchange,
            String isin
    ) {}

    public record SummaryDto(
            int total,
            int matched,
            int unmatched,
            int duplicates,
            int errors,
            String note
    ) {}
}
