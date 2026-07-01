package com.financeos.domain.ingestion;

import java.util.List;

public record FileIngestionResult(
    int filesProcessed,
    int totalCreated,
    int totalDuplicatesFound,
    List<FileSummary> fileDetails
) {
    public record FileSummary(
        String filename,
        String status, // "SUCCESS", "FAILED"
        int linesParsed,
        String errorMessage
    ) {}
}
